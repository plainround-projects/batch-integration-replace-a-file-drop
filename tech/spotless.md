# Spotless and google-java-format

## What it is

A **formatter** rewrites source code into one fixed layout (indentation, line breaks, spaces, order of imports), so every file looks the same no matter who wrote it and reviews never argue about style. **google-java-format** is a formatter that applies Google's Java style (two-space indentation, a hundred-character line limit). **Spotless** is the tool that runs formatters from the build: `spotless:check` fails if any file would change, `spotless:apply` rewrites the files.

## Why this repository uses it

The delivery rules ask for a formatter run by the build. One style, enforced automatically, means code reviews talk about behavior, not spaces.

## Where it appears

| Path | What it does with Spotless |
| --- | --- |
| `pom.xml` lines 230 to 243 | Configures Spotless with google-java-format. |
| `Makefile` | `make lint` runs `spotless:check`; `make format` runs `spotless:apply`. |
| `.github/workflows/ci.yml` | Runs `make lint` on every push. |

## How it works underneath

Spotless parses each `.java` file with the formatter, produces the formatted text and compares it with the file. `check` reports a difference as a build failure; `apply` writes the formatted text back. Because the formatter version is pinned (`google-java-format.version`), the output is the same on every machine, which is what makes the check meaningful in continuous integration. `removeUnusedImports` additionally deletes `import` lines that nothing uses.

## Walkthrough of this repository's use

```xml
      <plugin>
        <groupId>com.diffplug.spotless</groupId>
        <artifactId>spotless-maven-plugin</artifactId>
        <version>${spotless.version}</version>
        <configuration>
          <java>
            <googleJavaFormat>
              <version>${google-java-format.version}</version>
              <style>GOOGLE</style>
            </googleJavaFormat>
            <removeUnusedImports/>
          </java>
        </configuration>
      </plugin>
```

`<java>` configures the Java formatting step. `<googleJavaFormat>` selects the formatter, `<version>` pins it to 1.28.0 through the property, and `<style>GOOGLE</style>` chooses Google's style (the alternative AOSP uses four-space indentation). `<removeUnusedImports/>` is an empty element switching on removal of unused `import` lines. The plug-in has no `<executions>`, so nothing runs by itself in the lifecycle; the Makefile calls `spotless:check` and `spotless:apply` explicitly.

## Try it yourself

1. Check the style: `./mvnw -q spotless:check`. Silence means every file is formatted.
2. Make a mess: add three spaces before a line in any class and run step 1; it fails and prints the file and a diff.
3. Fix it: `./mvnw -q spotless:apply`, then `git diff` shows the formatter undoing your spaces.
4. See the lint target: `make -n lint` prints the commands it would run.
5. Add an unused import (`import java.util.Map;`) to a class, run `spotless:apply`, and watch it disappear.

## Mistakes people make

- **Formatting by hand.** Run `make format` and let the tool decide.
- **Different formatter versions on different machines.** Pin the version, as `pom.xml` does.
- **Skipping the check in CI.** The `make lint` target runs `spotless:check` first, so unformatted code never reaches the main branch.

## Check yourself

1. What is the difference between `check` and `apply`? *`check` fails if a file would change; `apply` changes it.*
2. Why pin the formatter version? *So the output is identical everywhere.*
3. What style is used? *Google style.*
4. What does `removeUnusedImports` do? *Deletes imports that nothing uses.*
5. Where does the style check run in CI? *In the `make lint` step.*

## Further reading

- Spotless Maven plug-in 2.44.5: https://github.com/diffplug/spotless/tree/main/plugin-maven
- google-java-format 1.28.0: https://github.com/google/google-java-format
- Google Java Style Guide: https://google.github.io/styleguide/javaguide.html
