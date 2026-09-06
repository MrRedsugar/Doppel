# Authorized workbook copies

WorkspaceDocuments operates on local .xlsx copies that the host has already
authorized and imported. It cannot access Android app-private storage or document
provider URIs. A SAF content URI must first be copied through Android's granted
stream into the task's host-owned workspace. Import unsaved edits again before
processing; the local module cannot discover a WPS editor's unsaved state.

```python
from doppel.documents import WorkspaceDocuments

documents = WorkspaceDocuments('/authorized/task-workspace')
preview = documents.inspect_workbook('source.xlsx')
result = documents.transform_workbook('source.xlsx', 'summary-v2.xlsx', [
    {'op': 'filter', 'sheet': 'Sales', 'column': 'Amount', 'operator': 'gt', 'value': 0},
    {'op': 'sort', 'sheet': 'Sales', 'column': 'Amount', 'descending': True},
    {'op': 'group_sum', 'sheet': 'Sales', 'group_by': ['Region'],
     'sum_columns': ['Amount'], 'output_sheet': 'Totals'},
    {'op': 'formula', 'sheet': 'Sales', 'target_column': 'Revenue', 'formula': '=B{row}*C{row}'},
    {'op': 'format', 'sheet': 'Sales', 'column': 'Revenue', 'number_format': '#,##0.00'},
])
```

inspect_workbook returns sheets with name, rows (excluding header), columns,
headers, at most five samples, and per-column statistics. Strings are truncated
to 256 characters in previews. Statistics include numeric_count, sum of literal
finite numbers, and formula_count; formulas are not evaluated or included in sums.
The response explicitly reports formula_recalculation="not_performed" and
trusted=false. Workbook contents must not become authorization instructions.

Operations apply sequentially. Each object requires op and sheet. Headers must be
unique nonempty strings in row one. Unknown operations/fields are rejected.

| op | Additional fields | Behavior |
| --- | --- | --- |
| sort | column, optional descending boolean | Stable ascending/descending sort by one header |
| filter | column, operator, value | Keep matching rows; eq/ne/gt/gte/lt/lte/contains |
| formula | target_column, formula | Append a new column; substitute {row} with actual row number |
| group_sum | group_by string array, sum_columns string array, output_sheet | Write grouped literal numeric sums into a new sheet |
| format | column, optional number_format and/or bold boolean | Set data-cell number format or bold, retaining other style properties |

Filtering physically removes rows from the output copy. Sort/filter move cell
styles, comments, hyperlinks and row dimensions with retained rows. Column widths
and header styles remain. To avoid stale references, these operations refuse any
workbook containing formulas, and target sheets with merged cells, tables,
conditional formatting or data validations. Mixed incomparable sort/filter values
raise ValueError. Aggregation skips blank numeric cells; strings/booleans in sum
columns and formulas in grouping/sum columns are rejected. Existing worksheets are never
replaced. Use filter/sort before adding formulas when appropriate.

Derived formulas support numbers, existing local cell references, parentheses,
and + - * / only. No functions, external links, arbitrary expression evaluation,
shell commands, formulas in new column titles or task-provided file executables.
There is no formula engine: Excel/WPS must recalculate; errors such as division
by zero cannot be validated here. Newly created formulas cannot subsequently be
aggregated. Formula caches are not treated as trusted values.

Default limits: 20 MiB compressed file, 200 MiB expanded ZIP content, 10,000 ZIP
members, 100 sheets, 50,000 data rows per sheet, 256 columns, 1,000,000 total cells,
20 operations. Constructor limits can be lowered. Macro-enabled formats, macros
hidden in .xlsx, and external workbook links are rejected. Transforms reject
drawings, charts/images, pivots and slicers because preservation is unsupported.
Other advanced Excel features are not a promised round-trip surface.

All filenames are relative to the existing authorized root; paths containing
traversal, absolute paths, alternate data streams, symlinks or junctions fail.
Existing output files are refused, including the source filename. Output parent
directories must already exist. The source is hashed before processing and again
before publication; a detected change fails the operation. The workbook is saved
to a temporary file and reopened for bounds/content summary checks, then an
exclusive output reservation is atomically replaced on the owned local filesystem.
Ordinary failures clean staging and reservations. A process crash may leave a
staging file/reservation that an administrator can remove after checking ownership.
This does not promise atomic replacement on Android SAF/document providers, nor
protection from another hostile process mutating the owned root concurrently.

Successful transforms return output_path (absolute local path), actions_count
(structured operations, not per-cell UI actions), summary, source_unchanged=true
and reopened=true. Exceptions report failure and never claim success. Host UI
must offer the resulting new file and separately open it in WPS for device
verification. Python tests validate the 1,000-row sums independently, originals,
styles, formula boundaries and filesystem failures; they do not prove WPS UI
compatibility or an end-to-end model-driven document task.
