# Embedded Jina Reader extraction core

Upstream: https://github.com/jina-ai/reader
Revision: 1574bfd380d249c86c82db4dace0d9c8fe17e2b1
Original file: src/services/markify.ts
Vendored source: tools/jina-reader-core/vendor/markify.ts (unchanged)
License: Apache-2.0, JINA-LICENSE.txt

The page-local adapter and reproducible browser build are in
tools/jina-reader-core/. Run `npm ci`, `npm run build`, `npm test` there.
The output is an IIFE exposing
`DoppelReader.extract({url, selector?, maxChars?: number})` with the synchronous
result `{title: string, text: string, truncated: boolean}`. The full contract,
limits and smoke-check description are in that directory's README.md.

The bundle includes Mozilla Readability 0.6.0 (Apache-2.0), Jina Markify, and
@nomagick/mathml-to-latex 1.5.3 (MIT), including its embedded xmldom parser (MIT).
Complete original license texts are retained alongside this file:
READABILITY-LICENSE.txt, MATHML-LICENSE.txt, XMLDOM-LICENSE.txt, JINA-LICENSE.txt.
Exact build dependencies and package integrity hashes are in package-lock.json.

This adapter clones the rendered DOM, removes active elements and form/editor
content, uses Readability's detached element serializer when article detection
succeeds, and converts it through unmodified Markify. It never fetches URLs,
reads input values, navigates or invokes Android capabilities. No Puppeteer,
Node server, cloud credentials, model API, GeoLite data or LibreOffice is
included. Rendering, screenshots, network restrictions and reference paging
belong to the Android host. Existing PDFBox/POI document parsing remains local.

`reader-network.js` is a separate self-starting browser bundle from
tools/jina-reader-core/network.js. It uses @mswjs/interceptors 0.45.0's official
FetchInterceptor/XMLHttpRequestInterceptor browser entry points to carry
asynchronous Fetch/XHR requests through the host's bounded public HTTP bridge.
It is not part of Jina Markify and has no device/file/model capability.
Original MIT licenses: MSW-INTERCEPTORS-LICENSE.txt,
OPEN-DRAFT-UNTIL-LICENSE.txt, OUTVARIANT-LICENSE.txt, RETTIME-LICENSE.txt.
The upstream browser distribution also embeds debug/ms code; their MIT
licenses are retained in DEBUG-LICENSE.txt and MS-LICENSE.txt.
The full bridge contract and browser smoke check are documented in the tool's
README.md; Kotlin remains responsible for all network security and cancellation.
