# Jina Reader core for Doppel

This builds the page-local extraction core into the Android asset
`android/sdk/src/main/assets/third_party/jina-reader/reader-core.js`.
No Reader server, Node runtime, Chrome/Puppeteer, LibreOffice, or model client is
shipped by this bundle. Android owns rendering, network access, cancellation,
screenshots, and its existing bounded reference tools. PDF and Office text
continue through `ChatDocumentText` and the existing PDFBox/POI dependencies.

## Rebuild and check

From this directory:

```sh
npm ci
npm run build
npm test
```

Node 22+ is required for the build tooling. All direct versions and the complete
resolved dependency tree are pinned in package.json/package-lock.json. esbuild
and linkedom are development tools only; linkedom is used by the smoke check,
not the browser asset.

The network smoke check uses an installed Chrome/Edge/Chromium executable
(`DOPPEL_CHROMIUM` may select it). It downloads no browser, uses an isolated
temporary profile, disables DNS/background networking, and replaces native
fetch/XHR sends with failing guards. It verifies actual browser Fetch/XHR
interception, UTF-8 JSON and binary bodies, headers/status, bounds, bridge
errors, and unsupported WebView initialization.

## Public network bridge

`reader-network.js` is a separate self-starting IIFE. Load it as the first
script in the isolated page, after the host's CSP meta and before page code.
It uses the official `@mswjs/interceptors` 0.45.0 browser-only FetchInterceptor
and XMLHttpRequestInterceptor entry points and their `request` /
`controller.respondWith(new Response(...))` API. No Fetch/XHR implementation,
Node modules, service worker, or request passthrough is added by this adapter.
The build checks for external imports and accidentally selected Node entries.

The only Android capability it calls is the synchronous public network bridge:

```js
DoppelReaderNetwork.request(JSON.stringify({
  url: 'https://example.com/api', method: 'POST',
  headers: { 'content-type': 'application/json' }, body: 'eyJpZCI6MX0=',
}))
// JSON string: {status: 200, headers: {...}, body: '<base64>', error?: '...'}
```

The adapter permits GET, HEAD, POST, OPTIONS and preserves request body bytes.
It limits bodies to 128 KiB and responses to 5 MiB; any bridge/decoding error
fails that request instead of passing it to native networking. HEAD and
204/205/304 responses have no body. The host must independently enforce these
limits, public DNS and redirects, remove cookie/authentication headers, handle
cancellation, and exclude file/device/model capabilities. If the host returns
decompressed bytes, it must remove Content-Encoding, Content-Length and
Transfer-Encoding from its response headers.

`DoppelReaderNetworkStatus` is `{ready: true}` after installation, or
`{ready: false, error: '...'}` on initialization failure. The asset requires
WebView APIs including `Promise.withResolvers` and `URL.canParse`; it does not
polyfill them. Failure leaves subsequent page scripts and static extraction
available. The upstream interceptor supports asynchronous XHR only; synchronous
XHR is not intercepted. Native networking and non-Fetch/XHR channels therefore
remain blocked by the host. A synchronous Android bridge runs on its bridge
thread, while the calling page JavaScript waits for its return.

## JavaScript contract

After evaluating the asset in the isolated page:

```js
JSON.stringify(DoppelReader.extract({
  url: 'https://example.com/current-page',
  // selector: 'main',
  maxChars: 200000,
}))
// { "title": "...", "text": "Markdown...", "truncated": false }
```

The function is synchronous and returns a plain object. `url` defaults to
document.baseURI and must use HTTP or HTTPS. `selector`, when given, is a CSS
selector selecting the first matching element in the cleaned clone; it skips
article detection. An invalid/unmatched selector throws instead of silently
returning the full page. An omitted selector uses Mozilla Readability, falling
back to the cleaned body when article detection cannot produce content.

`maxChars` is an integer from 1 through 200000, default 200000. It limits `text`
in JavaScript UTF-16 units, matching the existing Android character offsets.
Truncation never leaves half a surrogate pair. Consequently a limit of one on
a leading supplementary character returns an empty, truncated text. It is not
a model token limit. `title` is page/article metadata and is outside this text
limit. Empty source content and documents with more than 20000 elements throw.

The adapter only reads a detached DOM clone. It never calls fetch, navigates,
executes page script, attaches the cloned nodes to the live page, invokes a
device bridge, or reads input `.value` properties. It excludes form controls,
editable content, scripts, styles, templates, frames and embedded active
content. Readability returns its detached element through `serializer`, so the
adapter does not reparse article HTML into a browser document. Markdown and
URLs are untrusted reference data; Android must retain its URL/network policy
and tool-result bounds.

## Upstream source

`vendor/markify.ts` is copied unchanged from:

- https://github.com/jina-ai/reader/blob/1574bfd380d249c86c82db4dace0d9c8fe17e2b1/src/services/markify.ts
- Revision: `1574bfd380d249c86c82db4dace0d9c8fe17e2b1`.
- Apache-2.0, original license in `vendor/LICENSE` and the generated Android
  license asset. Original copyright: Jina AI Limited.

The current upstream core is `MarkifyService`, not a Turndown dependency.
Its public `markify(element)`, options and `addRule` API operate directly on DOM
elements. It has one import: `@nomagick/mathml-to-latex`. The browser adapter in
`index.js` is Doppel-specific; the upstream module itself is not edited.

Bundled libraries:

- `@mozilla/readability` 0.6.0, Apache-2.0; original LICENSE.md copied.
- `@nomagick/mathml-to-latex` 1.5.3, MIT; original LICENSE.md copied. Its
  published JS distribution already bundles XML parser code. The package's
  declared `@xmldom/xmldom` dependency is locked to 0.8.15 by npm; this does not
  identify the version embedded by the upstream MathML build.
- `@xmldom/xmldom`, MIT; original parser license copied as XMLDOM-LICENSE.txt.
- `@mswjs/interceptors` 0.45.0, MIT, with browser runtime dependencies
  `@open-draft/until`, `outvariant`, and `rettime`, plus its prebundled `debug`
  and `ms` code; their complete original
  licenses are copied alongside the network asset. Exact transitive versions
  and package integrity hashes are in package-lock.json.

No `licensed/` assets from the Jina service are copied. In particular, this
module does not require GeoLite databases, fonts, or server user-agent lists.
