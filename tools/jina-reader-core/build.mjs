import { build } from 'esbuild';
import { mkdir, copyFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const output = new URL('../../android/sdk/src/main/assets/third_party/jina-reader/', import.meta.url);
await mkdir(output, { recursive: true });
await build({
  entryPoints: [fileURLToPath(new URL('index.js', import.meta.url))],
  outfile: fileURLToPath(new URL('reader-core.js', output)),
  bundle: true, format: 'iife', globalName: 'DoppelReader', platform: 'browser', target: 'chrome70',
  minify: true, legalComments: 'inline',
});
const network = await build({
  entryPoints: [fileURLToPath(new URL('network.js', import.meta.url))],
  outfile: fileURLToPath(new URL('reader-network.js', output)),
  bundle: true, format: 'iife', platform: 'browser', target: 'chrome70',
  minify: true, legalComments: 'inline', metafile: true,
  banner: { js: 'window.DoppelReaderNetworkStatus={ready:false,error:"Network interceptor initialization failed"};' },
});
if (Object.values(network.metafile.outputs).some(file => file.imports.length) ||
    Object.keys(network.metafile.inputs).some(path => /\/lib\/node\//.test(path)))
  throw new Error('Network asset must be self-contained and browser-only');
await copyFile(new URL('vendor/LICENSE', import.meta.url), new URL('JINA-LICENSE.txt', output));
for (const [input, name] of [
  ['@mozilla/readability/LICENSE.md', 'READABILITY-LICENSE.txt'],
  ['@nomagick/mathml-to-latex/LICENSE.md', 'MATHML-LICENSE.txt'],
  ['@xmldom/xmldom/LICENSE', 'XMLDOM-LICENSE.txt'],
  ['@mswjs/interceptors/LICENSE.md', 'MSW-INTERCEPTORS-LICENSE.txt'],
  ['@open-draft/until/LICENSE', 'OPEN-DRAFT-UNTIL-LICENSE.txt'],
  ['outvariant/LICENSE', 'OUTVARIANT-LICENSE.txt'],
  ['rettime/LICENSE.md', 'RETTIME-LICENSE.txt'],
  ['debug/LICENSE', 'DEBUG-LICENSE.txt'],
  ['ms/license.md', 'MS-LICENSE.txt'],
]) await copyFile(new URL(`node_modules/${input}`, import.meta.url), new URL(name, output));
