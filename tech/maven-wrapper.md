# Maven Wrapper

## What it is

The Maven Wrapper is a small set of files committed to the repository so that anyone can build the project without first installing Maven. The files are a shell script (`mvnw`, for macOS and Linux), a Windows script (`mvnw.cmd`), and two settings files under `.mvn/`. When you run `./mvnw package`, the script checks whether the exact Maven version the project wants is already stored on your machine; if not, it downloads it once into `~/.m2/wrapper/dists/`, and then it runs that Maven with your arguments. The version is written in the repository, so everyone, and the CI machine, builds with the same Maven.

## Why this repository uses it

A build that depends on "whatever Maven is installed" gives different results on different machines. With the wrapper the only prerequisite is a JDK. Every `make` target that builds Java calls `./mvnw`, and the `Dockerfile` copies the wrapper files so the container builds the same way.

## Where it appears

| Path | What it does |
| --- | --- |
| `mvnw` | The macOS/Linux launcher (a POSIX shell script of about 300 lines). |
| `mvnw.cmd` | The Windows launcher: one file that is both a batch file and a PowerShell script. |
| `.mvn/wrapper/maven-wrapper.properties` | Names the Maven version to download. |
| `.mvn/jvm.config` | Options passed to the JVM that runs Maven itself. |
| `Makefile`, `Dockerfile`, `.github/workflows/ci.yml` | Call `./mvnw`. |

## How it works underneath

The launcher reads `distributionUrl` from `.mvn/wrapper/maven-wrapper.properties` (a **properties file**: lines of `key=value`, `#` comments). It turns that URL into a folder name under `~/.m2/wrapper/dists/`: the file name without `.zip`/`-bin`, plus a **hash** of the URL (a short fingerprint, so two different URLs never share a folder). If the folder exists, it runs `bin/mvn` inside it and is done (this is the normal, fast case). If not, it creates a temporary folder, downloads the zip (with `wget`, else `curl`, else a tiny Java program it writes and compiles on the spot), optionally checks a SHA-256 checksum, unzips, moves the result to the final folder and runs it. The temporary folder is removed when the script ends, whatever the reason (`trap`).

`exec` is the key command at the end: it **replaces** the script's process with Maven instead of starting Maven as a child, so signals and exit codes pass straight through.

## Walkthrough of this repository's use

### The settings files

```properties
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip
```

`wrapperVersion` is the version of the wrapper scripts themselves. `distributionType=only-script` means the wrapper consists of the scripts only (no extra jar file to download first). `distributionUrl` is the exact download address: Maven 3.9.9, the binary zip, from Maven Central's mirror. Change this line to move the whole project to another Maven version.

```
--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
```

Maven runs as a Java program, and Maven reads this file for options to give that JVM. The six `--add-exports` lines open internal packages of the Java compiler (`jdk.compiler/com.sun.tools.javac...`) to all code (`ALL-UNNAMED`): the Spotless/google-java-format plug-in reads the compiler's syntax tree, which Java's module system hides by default on recent JDKs. `--enable-native-access=ALL-UNNAMED` allows libraries that call native code to do so without a warning on newer JDKs.

### `mvnw`: the shell launcher

The first nineteen lines are the Apache license text (comments). The notes start at the first real line.

```bash
#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------
```

Line 1 is the shebang: run with the POSIX shell. Lines 2 to 19 are comments (a license notice: the file is Apache 2.0 software).

```bash

# ----------------------------------------------------------------------------
# Apache Maven Wrapper startup batch script, version 3.3.4
#
# Optional ENV vars
# -----------------
#   JAVA_HOME - location of a JDK home dir, required when download maven via java source
#   MVNW_REPOURL - repo url base for downloading maven distribution
#   MVNW_USERNAME/MVNW_PASSWORD - user and password for downloading maven
#   MVNW_VERBOSE - true: enable verbose log; debug: trace the mvnw script; others: silence the output
# ----------------------------------------------------------------------------

set -euf
[ "${MVNW_VERBOSE-}" != debug ] || set -x

```

Lines 21 to 30 document the optional environment variables: `JAVA_HOME` (where the JDK is), `MVNW_REPOURL` (a different download site, e.g. a company mirror), `MVNW_USERNAME`/`MVNW_PASSWORD` (a login for it) and `MVNW_VERBOSE` (`true` prints progress, `debug` traces every command). Line 32, `set -euf`, turns on three safety switches: `-e` exit on the first failing command, `-u` fail on an unset variable, `-f` switch off filename globbing (so a `*` in a value is never expanded by accident). Line 33 uses `${MVNW_VERBOSE-}`, which expands to the variable's value or to nothing (and so does not trip `-u`): if `MVNW_VERBOSE` equals `debug` then `set -x` makes the shell print every command it runs.

```bash
# OS specific support.
native_path() { printf %s\\n "$1"; }
case "$(uname)" in
CYGWIN* | MINGW*)
  [ -z "${JAVA_HOME-}" ] || JAVA_HOME="$(cygpath --unix "$JAVA_HOME")"
  native_path() { cygpath --path --windows "$1"; }
  ;;
esac

```

Line 36 defines a function `native_path` that prints its argument unchanged (`printf %s\\n "$1"` prints the text and a newline). Lines 37 to 42 check the operating system: `uname` prints its name. On Cygwin or MinGW (Unix-like layers on Windows, patterns `CYGWIN*` and `MINGW*`) the paths must be converted: line 39 converts `JAVA_HOME` to a Unix path with `cygpath --unix`, and line 40 redefines `native_path` to convert the other way. On macOS and Linux nothing changes.

```bash
# set JAVACMD and JAVACCMD
set_java_home() {
  # For Cygwin and MinGW, ensure paths are in Unix format before anything is touched
  if [ -n "${JAVA_HOME-}" ]; then
    if [ -x "$JAVA_HOME/jre/sh/java" ]; then
      # IBM's JDK on AIX uses strange locations for the executables
      JAVACMD="$JAVA_HOME/jre/sh/java"
      JAVACCMD="$JAVA_HOME/jre/sh/javac"
    else
      JAVACMD="$JAVA_HOME/bin/java"
      JAVACCMD="$JAVA_HOME/bin/javac"

      if [ ! -x "$JAVACMD" ] || [ ! -x "$JAVACCMD" ]; then
        echo "The JAVA_HOME environment variable is not defined correctly, so mvnw cannot run." >&2
        echo "JAVA_HOME is set to \"$JAVA_HOME\", but \"\$JAVA_HOME/bin/java\" or \"\$JAVA_HOME/bin/javac\" does not exist." >&2
        return 1
      fi
    fi
  else
```

`set_java_home` finds the Java programs. If `JAVA_HOME` is set (line 47): on IBM's AIX the executables live in an unusual place `jre/sh/java` (lines 48 to 51); otherwise they are `$JAVA_HOME/bin/java` and `javac` (lines 53 and 54). Lines 56 to 60 check that both files exist and are executable (`-x`); if not they print the explanation to standard error (`>&2`) and `return 1`.

```bash
  else
    JAVACMD="$(
      'set' +e
      'unset' -f command 2>/dev/null
      'command' -v java
    )" || :
    JAVACCMD="$(
      'set' +e
      'unset' -f command 2>/dev/null
      'command' -v javac
    )" || :

    if [ ! -x "${JAVACMD-}" ] || [ ! -x "${JAVACCMD-}" ]; then
      echo "The java/javac command does not exist in PATH nor is JAVA_HOME set, so mvnw cannot run." >&2
      return 1
    fi
  fi
}
```

Without `JAVA_HOME` the function looks in the `PATH`. `command -v java` prints the full path of the first `java` found; `$(...)` captures it, and the odd quoting (`'set' +e`, `'command'`) stops any user alias or function named `set` or `command` from interfering. `|| :` ignores a failure (`:` does nothing and succeeds). Line 74 checks that both exist, else prints an explanation and fails.

```bash

# hash string like Java String::hashCode
hash_string() {
  str="${1:-}" h=0
  while [ -n "$str" ]; do
    char="${str%"${str#?}"}"
    h=$(((h * 31 + $(LC_CTYPE=C printf %d "'$char")) % 4294967296))
    str="${str#?}"
  done
  printf %x\\n $h
}

verbose() { :; }
[ "${MVNW_VERBOSE-}" != true ] || verbose() { printf %s\\n "${1-}"; }

die() {
  printf %s\\n "$1" >&2
  exit 1
}

trim() {
  # MWRAPPER-139:
  #   Trims trailing and leading whitespace, carriage returns, tabs, and linefeeds.
  #   Needed for removing poorly interpreted newline sequences when running in more
  #   exotic environments such as mingw bash on Windows.
  printf "%s" "${1}" | tr -d '[:space:]'
}

scriptDir="$(dirname "$0")"
scriptName="$(basename "$0")"

```

`hash_string` computes the same number as Java's `String.hashCode()` for a text, in pure shell, so the folder name does not depend on any tool. It loops over the characters (`${str%"${str#?}"}` is the first character: `${str#?}` removes the first character, and `${str%...}` removes that remainder from the end), converts each to its character code with `printf %d "'$char"`, and updates the hash `h = (h * 31 + code) mod 2^32`. `printf %x\\n $h` prints it in hexadecimal. `verbose` is a function that does nothing (`:`) unless `MVNW_VERBOSE` is `true`, when it is redefined to print. `die` prints a message to standard error and exits with 1. `trim` removes all whitespace with `tr -d '[:space:]'`, needed because on Windows shells a line may end with an invisible carriage return.

```bash
scriptDir="$(dirname "$0")"
scriptName="$(basename "$0")"

# parse distributionUrl and optional distributionSha256Sum, requires .mvn/wrapper/maven-wrapper.properties
while IFS="=" read -r key value; do
  case "${key-}" in
  distributionUrl) distributionUrl=$(trim "${value-}") ;;
  distributionSha256Sum) distributionSha256Sum=$(trim "${value-}") ;;
  esac
done <"$scriptDir/.mvn/wrapper/maven-wrapper.properties"
[ -n "${distributionUrl-}" ] || die "cannot read distributionUrl property in $scriptDir/.mvn/wrapper/maven-wrapper.properties"
```

Lines 108 and 109 store the script's folder (`dirname "$0"`) and its name (`basename "$0"`; the name matters because `mvnw` runs `mvn` and a renamed `mvnw-xyz` would run `mvn-xyz`). Lines 112 to 117 read the properties file with a `while read` loop: `IFS="="` splits each line at the first `=`, `read -r key value` reads the two parts (`-r` keeps backslashes), and the `case` stores `distributionUrl` and `distributionSha256Sum` (trimmed). `done <"$scriptDir/.mvn/wrapper/maven-wrapper.properties"` feeds the file into the loop. Line 118 stops with a message if no URL was found.

```bash

case "${distributionUrl##*/}" in
maven-mvnd-*bin.*)
  MVN_CMD=mvnd.sh _MVNW_REPO_PATTERN=/maven/mvnd/
  case "${PROCESSOR_ARCHITECTURE-}${PROCESSOR_ARCHITEW6432-}:$(uname -a)" in
  *AMD64:CYGWIN* | *AMD64:MINGW*) distributionPlatform=windows-amd64 ;;
  :Darwin*x86_64) distributionPlatform=darwin-amd64 ;;
  :Darwin*arm64) distributionPlatform=darwin-aarch64 ;;
  :Linux*x86_64*) distributionPlatform=linux-amd64 ;;
  *)
    echo "Cannot detect native platform for mvnd on $(uname)-$(uname -m), use pure java version" >&2
    distributionPlatform=linux-amd64
    ;;
  esac
  distributionUrl="${distributionUrl%-bin.*}-$distributionPlatform.zip"
  ;;
maven-mvnd-*) MVN_CMD=mvnd.sh _MVNW_REPO_PATTERN=/maven/mvnd/ ;;
*) MVN_CMD="mvn${scriptName#mvnw}" _MVNW_REPO_PATTERN=/org/apache/maven/ ;;
esac

# apply MVNW_REPOURL and calculate MAVEN_HOME
# maven home pattern: ~/.m2/wrapper/dists/{apache-maven-<version>,maven-mvnd-<version>-<platform>}/<hash>
[ -z "${MVNW_REPOURL-}" ] || distributionUrl="$MVNW_REPOURL$_MVNW_REPO_PATTERN${distributionUrl#*"$_MVNW_REPO_PATTERN"}"
distributionUrlName="${distributionUrl##*/}"
distributionUrlNameMain="${distributionUrlName%.*}"
distributionUrlNameMain="${distributionUrlNameMain%-bin}"
MAVEN_USER_HOME="${MAVEN_USER_HOME:-${HOME}/.m2}"
MAVEN_HOME="${MAVEN_USER_HOME}/wrapper/dists/${distributionUrlNameMain-}/$(hash_string "$distributionUrl")"

```

The `case` on line 120 looks at the last part of the URL (`${distributionUrl##*/}` removes everything up to the final slash). If it is a Maven Daemon (`maven-mvnd-*`, a faster variant) the script chooses a platform build by inspecting the machine (`uname -a`, lines 123 to 132). Otherwise (line 136, the normal case) `MVN_CMD` is `mvn` plus whatever follows `mvnw` in the script's name (so `mvnw` runs `mvn`), and the URL pattern is `/org/apache/maven/`. Line 141 swaps the download site if `MVNW_REPOURL` is set. Lines 142 to 144 compute the folder name (`apache-maven-3.9.9`): remove the extension, then `-bin`. Line 145 picks the Maven user folder, `~/.m2` unless `MAVEN_USER_HOME` is set. Line 146 sets `MAVEN_HOME` to `~/.m2/wrapper/dists/apache-maven-3.9.9/<hash of the URL>`.

```bash
exec_maven() {
  unset MVNW_VERBOSE MVNW_USERNAME MVNW_PASSWORD MVNW_REPOURL || :
  exec "$MAVEN_HOME/bin/$MVN_CMD" "$@" || die "cannot exec $MAVEN_HOME/bin/$MVN_CMD"
}

if [ -d "$MAVEN_HOME" ]; then
  verbose "found existing MAVEN_HOME at $MAVEN_HOME"
  exec_maven "$@"
fi

case "${distributionUrl-}" in
*?-bin.zip | *?maven-mvnd-?*-?*.zip) ;;
*) die "distributionUrl is not valid, must match *-bin.zip or maven-mvnd-*.zip, but found '${distributionUrl-}'" ;;
esac

```

`exec_maven` unsets the download credentials (so Maven does not inherit them) and `exec`s Maven's script with all the original arguments (`"$@"` is the list of arguments, each kept intact); `|| die` runs only if `exec` itself fails. If `MAVEN_HOME` already exists (line 153) it logs and runs Maven right away: this is the usual path. Otherwise lines 158 to 161 check that the URL looks like a binary zip.

```bash
# prepare tmp dir
if TMP_DOWNLOAD_DIR="$(mktemp -d)" && [ -d "$TMP_DOWNLOAD_DIR" ]; then
  clean() { rm -rf -- "$TMP_DOWNLOAD_DIR"; }
  trap clean HUP INT TERM EXIT
else
  die "cannot create temp dir"
fi

mkdir -p -- "${MAVEN_HOME%/*}"

# Download and Install Apache Maven
verbose "Couldn't find MAVEN_HOME, downloading and installing it ..."
verbose "Downloading from: $distributionUrl"
verbose "Downloading to: $TMP_DOWNLOAD_DIR/$distributionUrlName"

# select .zip or .tar.gz
if ! command -v unzip >/dev/null; then
  distributionUrl="${distributionUrl%.zip}.tar.gz"
  distributionUrlName="${distributionUrl##*/}"
fi

# verbose opt
__MVNW_QUIET_WGET=--quiet __MVNW_QUIET_CURL=--silent __MVNW_QUIET_UNZIP=-q __MVNW_QUIET_TAR=''
[ "${MVNW_VERBOSE-}" != true ] || __MVNW_QUIET_WGET='' __MVNW_QUIET_CURL='' __MVNW_QUIET_UNZIP='' __MVNW_QUIET_TAR=v

```

Line 164 creates a temporary directory with `mktemp -d` and checks it exists. Lines 165 and 166 define `clean` (remove it) and `trap clean HUP INT TERM EXIT` so it runs when the script ends or is interrupted (hang-up, Ctrl-C, terminate). Line 171 creates the parent folder of `MAVEN_HOME`. Lines 174 to 176 log. Lines 179 to 182 prefer a zip but fall back to `.tar.gz` if `unzip` is missing. Lines 185 and 186 set the quiet or verbose flags of the download and unpack tools.

```bash
# normalize http auth
case "${MVNW_PASSWORD:+has-password}" in
'') MVNW_USERNAME='' MVNW_PASSWORD='' ;;
has-password) [ -n "${MVNW_USERNAME-}" ] || MVNW_USERNAME='' MVNW_PASSWORD='' ;;
esac

if [ -z "${MVNW_USERNAME-}" ] && command -v wget >/dev/null; then
  verbose "Found wget ... using wget"
  wget ${__MVNW_QUIET_WGET:+"$__MVNW_QUIET_WGET"} "$distributionUrl" -O "$TMP_DOWNLOAD_DIR/$distributionUrlName" || die "wget: Failed to fetch $distributionUrl"
elif [ -z "${MVNW_USERNAME-}" ] && command -v curl >/dev/null; then
  verbose "Found curl ... using curl"
  curl ${__MVNW_QUIET_CURL:+"$__MVNW_QUIET_CURL"} -f -L -o "$TMP_DOWNLOAD_DIR/$distributionUrlName" "$distributionUrl" || die "curl: Failed to fetch $distributionUrl"
elif set_java_home; then
  verbose "Falling back to use Java to download"
  javaSource="$TMP_DOWNLOAD_DIR/Downloader.java"
  targetZip="$TMP_DOWNLOAD_DIR/$distributionUrlName"
  cat >"$javaSource" <<-END
	public class Downloader extends java.net.Authenticator
	{
	  protected java.net.PasswordAuthentication getPasswordAuthentication()
	  {
	    return new java.net.PasswordAuthentication( System.getenv( "MVNW_USERNAME" ), System.getenv( "MVNW_PASSWORD" ).toCharArray() );
	  }
	  public static void main( String[] args ) throws Exception
	  {
	    setDefault( new Downloader() );
	    java.nio.file.Files.copy( java.net.URI.create( args[0] ).toURL().openStream(), java.nio.file.Paths.get( args[1] ).toAbsolutePath().normalize() );
	  }
	}
	END
  # For Cygwin/MinGW, switch paths to Windows format before running javac and java
  verbose " - Compiling Downloader.java ..."
  "$(native_path "$JAVACCMD")" "$(native_path "$javaSource")" || die "Failed to compile Downloader.java"
  verbose " - Running Downloader.java ..."
  "$(native_path "$JAVACMD")" -cp "$(native_path "$TMP_DOWNLOAD_DIR")" Downloader "$distributionUrl" "$(native_path "$targetZip")"
fi

```

Lines 189 to 192 normalise the optional login: if there is no password, both are cleared; if there is a password but no user, the user is cleared too (the form `${MVNW_PASSWORD:+has-password}` expands to the word only if the variable is non-empty). Lines 194 to 199 download with `wget` if available, else with `curl` (`-f` fail on HTTP errors, `-L` follow redirects, `-o` output file). Lines 200 to 223 are the last resort, used when a login is needed or neither tool exists: the script writes a small Java program to a file with a **here-document** (`cat >file <<-END ... END`: everything between the markers is the file's content; the `-` lets the lines be indented with Tabs), compiles it with `javac` and runs it to copy the URL to the zip file, using `MVNW_USERNAME`/`MVNW_PASSWORD` for authentication.

```bash
# If specified, validate the SHA-256 sum of the Maven distribution zip file
if [ -n "${distributionSha256Sum-}" ]; then
  distributionSha256Result=false
  if [ "$MVN_CMD" = mvnd.sh ]; then
    echo "Checksum validation is not supported for maven-mvnd." >&2
    echo "Please disable validation by removing 'distributionSha256Sum' from your maven-wrapper.properties." >&2
    exit 1
  elif command -v sha256sum >/dev/null; then
    if echo "$distributionSha256Sum  $TMP_DOWNLOAD_DIR/$distributionUrlName" | sha256sum -c - >/dev/null 2>&1; then
      distributionSha256Result=true
    fi
  elif command -v shasum >/dev/null; then
    if echo "$distributionSha256Sum  $TMP_DOWNLOAD_DIR/$distributionUrlName" | shasum -a 256 -c >/dev/null 2>&1; then
      distributionSha256Result=true
    fi
  else
    echo "Checksum validation was requested but neither 'sha256sum' or 'shasum' are available." >&2
    echo "Please install either command, or disable validation by removing 'distributionSha256Sum' from your maven-wrapper.properties." >&2
    exit 1
  fi
  if [ $distributionSha256Result = false ]; then
    echo "Error: Failed to validate Maven distribution SHA-256, your Maven distribution might be compromised." >&2
    echo "If you updated your Maven version, you need to update the specified distributionSha256Sum property." >&2
    exit 1
  fi
fi

```

If a checksum was given in the properties, it is verified: for the Maven Daemon this is refused (lines 228 to 231); otherwise it uses `sha256sum` or, on macOS, `shasum -a 256 -c` (`-c` check). The text `"$distributionSha256Sum  $file"` (two spaces) is the format these tools read from standard input (`-`). If the check fails the script prints a warning that the download might be compromised and exits. This repository's properties file sets no checksum, so this block is skipped.

```bash
# unzip and move
if command -v unzip >/dev/null; then
  unzip ${__MVNW_QUIET_UNZIP:+"$__MVNW_QUIET_UNZIP"} "$TMP_DOWNLOAD_DIR/$distributionUrlName" -d "$TMP_DOWNLOAD_DIR" || die "failed to unzip"
else
  tar xzf${__MVNW_QUIET_TAR:+"$__MVNW_QUIET_TAR"} "$TMP_DOWNLOAD_DIR/$distributionUrlName" -C "$TMP_DOWNLOAD_DIR" || die "failed to untar"
fi

# Find the actual extracted directory name (handles snapshots where filename != directory name)
actualDistributionDir=""

# First try the expected directory name (for regular distributions)
if [ -d "$TMP_DOWNLOAD_DIR/$distributionUrlNameMain" ]; then
  if [ -f "$TMP_DOWNLOAD_DIR/$distributionUrlNameMain/bin/$MVN_CMD" ]; then
    actualDistributionDir="$distributionUrlNameMain"
  fi
fi

# If not found, search for any directory with the Maven executable (for snapshots)
if [ -z "$actualDistributionDir" ]; then
  # enable globbing to iterate over items
  set +f
  for dir in "$TMP_DOWNLOAD_DIR"/*; do
    if [ -d "$dir" ]; then
      if [ -f "$dir/bin/$MVN_CMD" ]; then
        actualDistributionDir="$(basename "$dir")"
        break
      fi
    fi
  done
  set -f
fi

if [ -z "$actualDistributionDir" ]; then
  verbose "Contents of $TMP_DOWNLOAD_DIR:"
  verbose "$(ls -la "$TMP_DOWNLOAD_DIR")"
  die "Could not find Maven distribution directory in extracted archive"
fi

verbose "Found extracted Maven distribution directory: $actualDistributionDir"
printf %s\\n "$distributionUrl" >"$TMP_DOWNLOAD_DIR/$actualDistributionDir/mvnw.url"
mv -- "$TMP_DOWNLOAD_DIR/$actualDistributionDir" "$MAVEN_HOME" || [ -d "$MAVEN_HOME" ] || die "fail to move MAVEN_HOME"

clean || :
exec_maven "$@"
```

Lines 253 to 257 unpack (`unzip -q` or `tar xzf`). Lines 259 to 282 find the folder that was unpacked: first the expected name, otherwise search every folder for `bin/mvn`; `set +f` turns globbing back on for the loop and `set -f` off again. Lines 284 to 288 stop if nothing was found. Line 291 records the URL in a file `mvnw.url` inside the new folder. Line 292 moves the folder to its final place: `mv ... || [ -d "$MAVEN_HOME" ] || die` succeeds also if another process won the race and created the folder first. Line 294 cleans up, and line 295 starts Maven.

### `mvnw.cmd`: the Windows launcher

Windows has two scripting languages: **batch** (old, `.cmd`) and **PowerShell** (newer). This one file is both: the first line `<# : batch portion` is a valid batch command that starts a PowerShell comment `<# ... #>`. Windows batch runs the top part; the batch part starts PowerShell on the same file and PowerShell runs the part after the comment. Lines starting with `@REM` are batch comments.

```bat
<# : batch portion
@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------
```

```bat

@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script, version 3.3.4
@REM
@REM Optional ENV vars
@REM   MVNW_REPOURL - repo url base for downloading maven distribution
@REM   MVNW_USERNAME/MVNW_PASSWORD - user and password for downloading maven
@REM   MVNW_VERBOSE - true: enable verbose log; others: silence the output
@REM ----------------------------------------------------------------------------

@IF "%__MVNW_ARG0_NAME__%"=="" (SET __MVNW_ARG0_NAME__=%~nx0)
@SET __MVNW_CMD__=
@SET __MVNW_ERROR__=
@SET __MVNW_PSMODULEP_SAVE=%PSModulePath%
@SET PSModulePath=
@FOR /F "usebackq tokens=1* delims==" %%A IN (`powershell -noprofile "& {$scriptDir='%~dp0'; $script='%__MVNW_ARG0_NAME__%'; icm -ScriptBlock ([Scriptblock]::Create((Get-Content -Raw '%~f0'))) -NoNewScope}"`) DO @(
  IF "%%A"=="MVN_CMD" (set __MVNW_CMD__=%%B) ELSE IF "%%B"=="" (echo %%A) ELSE (echo %%A=%%B)
)
@SET PSModulePath=%__MVNW_PSMODULEP_SAVE%
@SET __MVNW_PSMODULEP_SAVE=
@SET __MVNW_ARG0_NAME__=
@SET MVNW_USERNAME=
@SET MVNW_PASSWORD=
@IF NOT "%__MVNW_CMD__%"=="" ("%__MVNW_CMD__%" %*)
@echo Cannot start maven from wrapper >&2 && exit /b 1
@GOTO :EOF
: end batch / begin powershell #>

```

The batch part. `@IF "%__MVNW_ARG0_NAME__%"=="" (SET __MVNW_ARG0_NAME__=%~nx0)` remembers the script's own name. Lines 31 and 32 clear two variables. Lines 33 and 34 save and empty `PSModulePath` (a PowerShell setting) so the user's modules do not interfere. Line 35 starts PowerShell (`powershell -noprofile`) with a command that reads this very file (`Get-Content -Raw '%~f0'`, where `%~f0` is the full path of the script) and runs it as a script block (`icm` is short for `Invoke-Command`); the `FOR /F` loop reads each line PowerShell prints, split at `=` into `%%A` and `%%B`. Line 36 stores a line named `MVN_CMD` into `__MVNW_CMD__` and echoes anything else. Lines 38 to 42 restore or clear variables. Line 43 runs the Maven command PowerShell found, passing all arguments (`%*`). Line 44 prints "Cannot start maven from wrapper" if that failed and exits with 1; line 45 jumps to the end of the file. Line 46 ends the batch part and starts the PowerShell part.

```bat
$ErrorActionPreference = "Stop"
if ($env:MVNW_VERBOSE -eq "true") {
  $VerbosePreference = "Continue"
}

# calculate distributionUrl, requires .mvn/wrapper/maven-wrapper.properties
$distributionUrl = (Get-Content -Raw "$scriptDir/.mvn/wrapper/maven-wrapper.properties" | ConvertFrom-StringData).distributionUrl
if (!$distributionUrl) {
  Write-Error "cannot read distributionUrl property in $scriptDir/.mvn/wrapper/maven-wrapper.properties"
}

switch -wildcard -casesensitive ( $($distributionUrl -replace '^.*/','') ) {
  "maven-mvnd-*" {
    $USE_MVND = $true
    $distributionUrl = $distributionUrl -replace '-bin\.[^.]*$',"-windows-amd64.zip"
    $MVN_CMD = "mvnd.cmd"
    break
  }
  default {
    $USE_MVND = $false
    $MVN_CMD = $script -replace '^mvnw','mvn'
    break
  }
}

# apply MVNW_REPOURL and calculate MAVEN_HOME
# maven home pattern: ~/.m2/wrapper/dists/{apache-maven-<version>,maven-mvnd-<version>-<platform>}/<hash>
if ($env:MVNW_REPOURL) {
  $MVNW_REPO_PATTERN = if ($USE_MVND -eq $False) { "/org/apache/maven/" } else { "/maven/mvnd/" }
  $distributionUrl = "$env:MVNW_REPOURL$MVNW_REPO_PATTERN$($distributionUrl -replace "^.*$MVNW_REPO_PATTERN",'')"
}
$distributionUrlName = $distributionUrl -replace '^.*/',''
$distributionUrlNameMain = $distributionUrlName -replace '\.[^.]*$','' -replace '-bin$',''

$MAVEN_M2_PATH = "$HOME/.m2"
```

PowerShell uses `$variables` and cmdlets named Verb-Noun. `$ErrorActionPreference = "Stop"` makes any error stop the script. `$VerbosePreference = "Continue"` shows verbose messages when `MVNW_VERBOSE` is `true`. `Get-Content -Raw ... | ConvertFrom-StringData` reads the properties file as a table of keys and values, and `.distributionUrl` picks one. `switch -wildcard -casesensitive` chooses on the last part of the URL (`-replace '^.*/',''` removes everything up to the final slash with a regular expression): a Maven Daemon URL is switched to the Windows build and `MVN_CMD` is `mvnd.cmd`; the normal case sets `$MVN_CMD` to the script name with `mvnw` replaced by `mvn`. Lines 75 to 80 apply `MVNW_REPOURL` and compute the folder name by stripping the extension and `-bin`.

```bat
$MAVEN_M2_PATH = "$HOME/.m2"
if ($env:MAVEN_USER_HOME) {
  $MAVEN_M2_PATH = "$env:MAVEN_USER_HOME"
}

if (-not (Test-Path -Path $MAVEN_M2_PATH)) {
    New-Item -Path $MAVEN_M2_PATH -ItemType Directory | Out-Null
}

$MAVEN_WRAPPER_DISTS = $null
if ((Get-Item $MAVEN_M2_PATH).Target[0] -eq $null) {
  $MAVEN_WRAPPER_DISTS = "$MAVEN_M2_PATH/wrapper/dists"
} else {
  $MAVEN_WRAPPER_DISTS = (Get-Item $MAVEN_M2_PATH).Target[0] + "/wrapper/dists"
}

$MAVEN_HOME_PARENT = "$MAVEN_WRAPPER_DISTS/$distributionUrlNameMain"
$MAVEN_HOME_NAME = ([System.Security.Cryptography.SHA256]::Create().ComputeHash([byte[]][char[]]$distributionUrl) | ForEach-Object {$_.ToString("x2")}) -join ''
$MAVEN_HOME = "$MAVEN_HOME_PARENT/$MAVEN_HOME_NAME"

if (Test-Path -Path "$MAVEN_HOME" -PathType Container) {
  Write-Verbose "found existing MAVEN_HOME at $MAVEN_HOME"
  Write-Output "MVN_CMD=$MAVEN_HOME/bin/$MVN_CMD"
  exit $?
}

if (! $distributionUrlNameMain -or ($distributionUrlName -eq $distributionUrlNameMain)) {
  Write-Error "distributionUrl is not valid, must end with *-bin.zip, but found $distributionUrl"
}

```

Lines 82 to 96 choose the Maven user folder (`$HOME/.m2` or `MAVEN_USER_HOME`), create it if missing, and follow a symbolic link if that folder is one (`(Get-Item ...).Target`). Lines 98 to 100 compute `MAVEN_HOME`: the folder for this distribution plus the SHA-256 hash of the URL in lowercase hexadecimal (`ComputeHash`, then `ToString("x2")` per byte, joined). If the folder already exists, line 104 prints `MVN_CMD=<path>` for the batch part to read and the script exits. Lines 108 to 110 check the URL ends in `-bin.zip`.

```bat
# prepare tmp dir
$TMP_DOWNLOAD_DIR_HOLDER = New-TemporaryFile
$TMP_DOWNLOAD_DIR = New-Item -Itemtype Directory -Path "$TMP_DOWNLOAD_DIR_HOLDER.dir"
$TMP_DOWNLOAD_DIR_HOLDER.Delete() | Out-Null
trap {
  if ($TMP_DOWNLOAD_DIR.Exists) {
    try { Remove-Item $TMP_DOWNLOAD_DIR -Recurse -Force | Out-Null }
    catch { Write-Warning "Cannot remove $TMP_DOWNLOAD_DIR" }
  }
}

New-Item -Itemtype Directory -Path "$MAVEN_HOME_PARENT" -Force | Out-Null

# Download and Install Apache Maven
Write-Verbose "Couldn't find MAVEN_HOME, downloading and installing it ..."
Write-Verbose "Downloading from: $distributionUrl"
Write-Verbose "Downloading to: $TMP_DOWNLOAD_DIR/$distributionUrlName"

$webclient = New-Object System.Net.WebClient
if ($env:MVNW_USERNAME -and $env:MVNW_PASSWORD) {
  $webclient.Credentials = New-Object System.Net.NetworkCredential($env:MVNW_USERNAME, $env:MVNW_PASSWORD)
}
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$webclient.DownloadFile($distributionUrl, "$TMP_DOWNLOAD_DIR/$distributionUrlName") | Out-Null

```

A temporary folder is created from a temporary file name (lines 113 to 115). The `trap { ... }` block (116 to 121) removes it if anything fails. Line 123 creates the parent of `MAVEN_HOME`. Lines 126 to 128 log. Lines 130 to 135 download: a `WebClient` object, credentials if both variables are set, TLS 1.2 forced, then `DownloadFile`.

```bat
# If specified, validate the SHA-256 sum of the Maven distribution zip file
$distributionSha256Sum = (Get-Content -Raw "$scriptDir/.mvn/wrapper/maven-wrapper.properties" | ConvertFrom-StringData).distributionSha256Sum
if ($distributionSha256Sum) {
  if ($USE_MVND) {
    Write-Error "Checksum validation is not supported for maven-mvnd. `nPlease disable validation by removing 'distributionSha256Sum' from your maven-wrapper.properties."
  }
  Import-Module $PSHOME\Modules\Microsoft.PowerShell.Utility -Function Get-FileHash
  if ((Get-FileHash "$TMP_DOWNLOAD_DIR/$distributionUrlName" -Algorithm SHA256).Hash.ToLower() -ne $distributionSha256Sum) {
    Write-Error "Error: Failed to validate Maven distribution SHA-256, your Maven distribution might be compromised. If you updated your Maven version, you need to update the specified distributionSha256Sum property."
  }
}

# unzip and move
Expand-Archive "$TMP_DOWNLOAD_DIR/$distributionUrlName" -DestinationPath "$TMP_DOWNLOAD_DIR" | Out-Null

# Find the actual extracted directory name (handles snapshots where filename != directory name)
$actualDistributionDir = ""

# First try the expected directory name (for regular distributions)
$expectedPath = Join-Path "$TMP_DOWNLOAD_DIR" "$distributionUrlNameMain"
$expectedMvnPath = Join-Path "$expectedPath" "bin/$MVN_CMD"
if ((Test-Path -Path $expectedPath -PathType Container) -and (Test-Path -Path $expectedMvnPath -PathType Leaf)) {
  $actualDistributionDir = $distributionUrlNameMain
}

# If not found, search for any directory with the Maven executable (for snapshots)
if (!$actualDistributionDir) {
  Get-ChildItem -Path "$TMP_DOWNLOAD_DIR" -Directory | ForEach-Object {
    $testPath = Join-Path $_.FullName "bin/$MVN_CMD"
    if (Test-Path -Path $testPath -PathType Leaf) {
      $actualDistributionDir = $_.Name
    }
  }
}

if (!$actualDistributionDir) {
  Write-Error "Could not find Maven distribution directory in extracted archive"
}

Write-Verbose "Found extracted Maven distribution directory: $actualDistributionDir"
Rename-Item -Path "$TMP_DOWNLOAD_DIR/$actualDistributionDir" -NewName $MAVEN_HOME_NAME | Out-Null
try {
  Move-Item -Path "$TMP_DOWNLOAD_DIR/$MAVEN_HOME_NAME" -Destination $MAVEN_HOME_PARENT | Out-Null
} catch {
  if (! (Test-Path -Path "$MAVEN_HOME" -PathType Container)) {
    Write-Error "fail to move MAVEN_HOME"
  }
} finally {
  try { Remove-Item $TMP_DOWNLOAD_DIR -Recurse -Force | Out-Null }
  catch { Write-Warning "Cannot remove $TMP_DOWNLOAD_DIR" }
}

Write-Output "MVN_CMD=$MAVEN_HOME/bin/$MVN_CMD"
```

If `distributionSha256Sum` is in the properties the file's SHA-256 is compared with it (`Get-FileHash`), erroring if different (lines 137 to 147). `Expand-Archive` unzips (line 150). Lines 152 to 174 find the unpacked folder: the expected name first, then any folder containing `bin/<MVN_CMD>`. Line 177 renames it to the hash name and line 178 to 187 moves it into place; the `catch` accepts failure if the folder now exists (another process won the race), and `finally` always removes the temporary folder. Line 189 prints `MVN_CMD=<path to mvn.cmd>`, the line the batch part reads.

## Try it yourself

1. Run it: `./mvnw -version`. The first line is `Apache Maven 3.9.9`, then the Java version. (The first run downloads Maven; later runs are instant.)
2. See where it stored Maven: `ls ~/.m2/wrapper/dists/`. You will see `apache-maven-3.9.9` with a hash folder inside.
3. Watch it work: `MVNW_VERBOSE=true ./mvnw -version` prints "found existing MAVEN_HOME at ...".
4. Trace every command: `MVNW_VERBOSE=debug ./mvnw -version 2>&1 | head -40`.
5. Compute the folder hash yourself: look at `ls ~/.m2/wrapper/dists/apache-maven-3.9.9/`; the folder name is the hash the script's `hash_string` function computes for the URL.

## Mistakes people make

- **Committing without the `.mvn/` folder.** `mvnw` needs `maven-wrapper.properties` and fails with "cannot read distributionUrl".
- **Losing the executable bit.** On macOS and Linux `mvnw` must be executable (`chmod +x mvnw`); git remembers the bit, but some zip tools do not.
- **Running `mvn` in one place and `./mvnw` in another.** Two different Maven versions give two different results.
- **A corporate proxy.** Downloads fail until `MVNW_REPOURL` (or the proxy settings) is set.
- **Editing the generated scripts.** They are produced by the wrapper plug-in; upgrade with `mvn wrapper:wrapper -Dmaven=<version>` instead.

## Check yourself

1. Where does the wrapper keep the Maven it downloads? *In `~/.m2/wrapper/dists/<name>/<hash>/`.*
2. Why is the hash of the URL part of the folder name? *So two different URLs never share a folder.*
3. What does `exec` do in the last line? *Replaces the script's process with Maven, so signals and exit codes pass through.*
4. What does `trap clean HUP INT TERM EXIT` guarantee? *The temporary folder is removed when the script ends or is interrupted.*
5. What changes if you edit `distributionUrl`? *The next run downloads and uses that Maven version.*

## Further reading

- Maven Wrapper: https://maven.apache.org/tools/wrapper/
- Wrapper 3.3.4 source (the version in this repository): https://github.com/apache/maven-wrapper/tree/maven-wrapper-3.3.4
- Maven 3.9.9 release: https://maven.apache.org/docs/3.9.9/release-notes.html
