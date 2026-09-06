Use a group_sum operation to summarize literal numeric values. For example:

```json
{"op":"group_sum","sheet":"Sales","group_by":["Region"],"sum_columns":["Amount"],"output_sheet":"Totals"}
```

Column names are actual row-one headers. Formula values cannot be aggregated
without a trusted recalculation stage. Keep the original imported copy unchanged.
