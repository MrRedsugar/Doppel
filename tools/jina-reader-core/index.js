import { Readability } from '@mozilla/readability';
import { MarkifyService } from './vendor/markify.ts';

/** Read the rendered DOM only. No fetching, input values, or native bridges. */
export function extract({ url = document.baseURI, selector, maxChars = 200000 } = {}) {
  const source = new URL(url);
  if (!['http:', 'https:'].includes(source.protocol)) throw new Error('Expected an HTTP(S) source URL');
  if (!Number.isInteger(maxChars) || maxChars < 1 || maxChars > 200000) throw new Error('maxChars must be between 1 and 200000');
  if (selector !== undefined && (typeof selector !== 'string' || !selector.trim() || selector.length > 1024)) throw new Error('Invalid content selector');
  // ponytail: bound the recursive upstream DOM walk; larger pages need incremental extraction.
  if (document.getElementsByTagName('*').length > 20000) throw new Error('Page exceeds the DOM extraction limit');

  const copy = document.cloneNode(true);
  copy.querySelectorAll('script,style,noscript,template,iframe,frame,object,embed,input,textarea,select,button,[contenteditable]')
    .forEach(element => element.remove());
  let root = selector === undefined ? copy.body : copy.querySelector(selector);
  if (!root) throw new Error('Content selector did not match readable content');
  let title = copy.title || '';

  if (selector === undefined) {
    let article;
    try {
      article = new Readability(copy.cloneNode(true), {
        maxElemsToParse: 20000,
        serializer: element => element,
      }).parse();
    } catch { /* The sanitized body remains available when article detection fails. */ }
    if (article?.content && article.textContent?.trim()) {
      root = article.content;
      title = article.title || title;
    }
  }

  const formatter = new MarkifyService({
    baseUrl: source.href, gfm: true, headingStyle: 'atx', codeBlockStyle: 'fenced', fence: '```',
  });
  // Resolve image references without attaching the cloned tree or loading resources.
  root.querySelectorAll('img[src]').forEach(image => {
    try {
      const target = new URL(image.getAttribute('src'), source.href);
      if (['http:', 'https:'].includes(target.protocol)) image.setAttribute('src', target.href);
      else image.removeAttribute('src');
    } catch { image.removeAttribute('src'); }
  });
  const markdown = formatter.markify(root).trim();
  if (!markdown) throw new Error('Page has no readable text');
  let end = Math.min(markdown.length, maxChars);
  if (end < markdown.length && end > 0 && /[\uD800-\uDBFF]/.test(markdown[end - 1]) && /[\uDC00-\uDFFF]/.test(markdown[end])) end--;
  return { title, text: markdown.slice(0, end), truncated: end < markdown.length };
}
