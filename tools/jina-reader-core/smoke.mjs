import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { runInNewContext } from 'node:vm';
import { parseHTML } from 'linkedom';

const bundle = await readFile(new URL('../../android/sdk/src/main/assets/third_party/jina-reader/reader-core.js', import.meta.url), 'utf8');
function page(body, title = 'Reference title') {
  const { document } = parseHTML(`<html><head><title>${title}</title></head><body>${body}</body></html>`);
  const scope = { document, URL, fetch: () => { throw new Error('Extraction attempted a network request'); } };
  runInNewContext(bundle, scope);
  return { document, extract: options => scope.DoppelReader.extract({ url: 'https://example.com/docs/page', ...options }) };
}

const sample = page(`<article id="source"><h1>中文 reference</h1><p>Read <a href="../guide">the guide</a>.</p>
  <table><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody><tr><td>Radius</td><td>16 px</td></tr></tbody></table>
  <pre><code>const size = 16;\nconsole.log(size);</code></pre><p>你好 🌍</p>
  <img src="../diagram.png" alt="Diagram"><math><mi>x</mi></math></article>
  <aside>Do not include this sidebar</aside><script>throw new Error('Do not execute')</script>`);
const original = sample.document.documentElement.outerHTML;
const selected = sample.extract({ selector: '#source' });
assert.equal(selected.title, 'Reference title');
assert.match(selected.text, /# 中文 reference/);
assert.match(selected.text, /\[the guide\]\(https:\/\/example.com\/guide\)/);
assert.match(selected.text, /\| Name \| Value \|/);
assert.match(selected.text, /\| Radius \| 16 px \|/);
assert.match(selected.text, /```[\s\S]*const size = 16;/);
assert.match(selected.text, /你好 🌍/);
assert.match(selected.text, /https:\/\/example.com\/diagram.png/);
assert.match(selected.text, /\$x\$/);
assert.doesNotMatch(selected.text, /sidebar|Do not execute/);
assert.equal(selected.truncated, false);
assert.equal(sample.document.documentElement.outerHTML, original);

const article = page(`<nav>Navigation</nav><article><h1>Long article</h1>${'<p>This is a complete paragraph with enough content to identify the article and preserve its meaning.</p>'.repeat(12)}</article>`);
assert.match(article.extract().text, /complete paragraph/);
assert.doesNotMatch(article.extract().text, /Navigation/);
assert.equal(page('<div>Short body fallback.</div>').extract().text, 'Short body fallback.');
const privatePage = page('<p>Public content.</p><input value="SECRET_INPUT"><textarea>SECRET_TEXTAREA</textarea><select><option>SECRET_SELECTION</option></select><div contenteditable="true">SECRET_EDIT</div>');
Object.defineProperty(privatePage.document.querySelector('input'), 'value', { get() { throw new Error('Input value was read'); } });
assert.doesNotMatch(privatePage.extract().text, /SECRET/);

const unicode = page('<p id="unicode">A😀B</p>');
assert.equal(unicode.extract({ selector: '#unicode', maxChars: 2 }).text, 'A');
assert.equal(unicode.extract({ selector: '#unicode', maxChars: 2 }).truncated, true);
assert.equal(unicode.extract({ selector: '#unicode', maxChars: 3 }).text, 'A😀');
assert.equal(unicode.extract({ selector: '#unicode', maxChars: 4 }).truncated, false);
assert.throws(() => sample.extract({ selector: '#missing' }), /did not match/);
assert.throws(() => sample.extract({ selector: '[' }));
assert.throws(() => sample.extract({ maxChars: 0 }), /maxChars/);
assert.throws(() => sample.extract({ maxChars: 200001 }), /maxChars/);
assert.throws(() => sample.extract({ url: 'file:///private' }), /HTTP/);
assert.throws(() => page('<input value="private">').extract(), /no readable text/);
console.log('Reader core smoke passed: article/fallback, selector, Markdown, MathML, Unicode, inert DOM, private inputs, validation.');
