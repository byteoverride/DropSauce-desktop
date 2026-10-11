# Icons

The `.svg` files in this directory are **Material Symbols** (Rounded, 24px,
weight 400), from <https://github.com/google/material-design-icons>, used
unmodified.

Copyright Google LLC. Licensed under the Apache License, Version 2.0:
<https://www.apache.org/licenses/LICENSE-2.0>

Apache-2.0 is compatible with this project's GPL-3.0 licence in this
direction, so the combined work remains GPL-3.0.

## Why they are vendored rather than depended on

Measured, not assumed:

| option | size | why not |
|---|---|---|
| `material-icons-extended` | 36.9 MB | and deprecated, pinned to 1.7.3 |
| `material-icons-core` | 857 KB | only 48 icons; no download, history, stats, folder, backup, bookmark, filter, sort or view toggle |
| these files | 200 KB | chosen |

They are loaded with `androidx.compose.ui.res.painterResource("icons/<name>.svg")`,
the same API the window icon already uses, so no dependency is involved at all.
`IconTintTest` renders one offscreen and reads the pixels back, because an icon
that loads is not the same as an icon that takes the theme's colour: these files
carry no fill of their own and would draw solid black if the tint were not
applied.
