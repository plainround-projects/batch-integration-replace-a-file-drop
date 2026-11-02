# EditorConfig

## What it is

**EditorConfig** is a small file named `.editorconfig` that tells every text editor and IDE the same basic formatting rules for a project: which character set, which line ending, whether to use tabs or spaces, how wide an indent is, whether to remove spaces at the end of lines. Editors that support it (most do, some with a plug-in) read the file when you open a file in the project and follow it, so two people using different editors write files that look the same and produce no noisy differences in version control.

## Why this repository uses it

The repository has five kinds of file with different needs: Java (4-space indent, formatted by a tool), YAML and JSON (2-space indent), a Makefile (which *requires* tab characters), Markdown (where two spaces at the end of a line mean a line break), and shell scripts (which must have Unix line endings). One file states all of it for any editor.

## Where it appears

| Path | What it does |
| --- | --- |
| `.editorconfig` | The rules, in sections by file pattern. |

## How it works underneath

The file is a list of **sections** in square brackets, each a file pattern (`[*]` means every file, `[*.{yml,yaml,json,toml}]` means files with one of those extensions, `[Makefile]` means that exact name), followed by `key = value` lines. The editor looks for `.editorconfig` in the file's folder and each parent folder, from the nearest upward, until it finds one with `root = true`. Later sections override earlier ones for files that match both.

## Walkthrough of this repository's use

```
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 4

[*.{yml,yaml,json,toml}]
indent_size = 2

[Makefile]
indent_style = tab

[*.md]
trim_trailing_whitespace = false
```

`root = true` (line 1) stops the search upward. `[*]` (line 3) applies to every file: `charset = utf-8` is the character encoding; `end_of_line = lf` is the Unix line ending (a single "line feed" character; Windows uses two characters, which would break a shell script with an error like `/bin/sh^M: bad interpreter`); `insert_final_newline = true` makes every file end with a newline (a Unix convention that many tools expect and that avoids "no newline at end of file" diffs); `trim_trailing_whitespace = true` removes spaces at the ends of lines; `indent_style = space` and `indent_size = 4` mean four spaces per indent level. `[*.{yml,yaml,json,toml}]` (line 11) narrows the indent to 2 for those formats. `[Makefile]` (line 14) sets `indent_style = tab` because `make` recipes must start with a tab character (the Make note). `[*.md]` (line 17) turns trailing-whitespace trimming off, because in Markdown two trailing spaces can be a deliberate line break.

## Try it yourself

1. Read the file: `cat .editorconfig`.
2. Check that Makefile lines start with a tab: `grep -c "^	" Makefile` (type a real Tab after the caret with `Ctrl+V` then `Tab`) prints the number of recipe lines.
3. Check that no file has Windows line endings: `grep -rl $'\r' --include=*.java --include=*.sh --include=Makefile . | head` prints nothing.
4. Check that the Java files end with a newline: `for f in $(git ls-files '*.java' | head -5); do tail -c1 "$f" | xxd -p; done` prints `0a` for each.
5. See an editor use it: open `Makefile` in an editor with EditorConfig support, press Tab, and see that a real tab character is inserted; open a `.yml` file and see two spaces.

## Mistakes people make

- **Spaces in a Makefile.** `make` reports `missing separator`.
- **Windows line endings in a shell script.** The shell reads the carriage return as part of the command.
- **Thinking EditorConfig formats code.** It only tells the editor; the Java formatter (Spotless) enforces the Java layout.
- **Forgetting `root = true`.** A stray `.editorconfig` in a parent folder could change the rules.

## Check yourself

1. What is `.editorconfig` for? *To give every editor the same basic formatting rules for the project.*
2. Why does `[Makefile]` use tabs? *`make` recipes must start with a tab.*
3. Why is trailing-whitespace trimming off for Markdown? *Two trailing spaces are a line break in Markdown.*
4. What does `root = true` do? *Stops the search for more `.editorconfig` files in parent folders.*
5. Does it replace the Java formatter? *No; Spotless enforces the Java layout.*

## Further reading

- EditorConfig specification: https://spec.editorconfig.org/
- EditorConfig home: https://editorconfig.org/
