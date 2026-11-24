#!/bin/bash

# Gradle start up script for UN*X

APP_HOME=$(cd "$(dirname "$0")/.." && pwd)

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

warn() {
    echo "$*"
}

die() {
    echo
    echo "$*"
    echo
    exit 1
}

# OS specific support.
cygwin=false
msys=false
darwin=false
stoppered=false
case "$(uname)" in
    CYGWIN* ) cygwin=true ;;
    Darwin* ) darwin=true ;;
    MINGW* ) msys=true ;;
esac

# Attempt to set APP_HOME
if [ -z "$APP_HOME" ]; then
    APP_HOME=$(cd "$(dirname "$0")/.." && pwd)
fi

# For Cygwin or MSYS, switch paths to Windows format before running java
if $cygwin || $msys; then
    APP_HOME=$(cygpath --path --mixed "$APP_HOME")
    CLASSPATH=$(cygpath --path --mixed "$CLASSPATH")
    JAVACMD=$(cygpath --unix "$JAVACMD")
    if [ "$CYGWIN" = "true" ]; then
        # Prevent Windows from treating @ as command separator
        CLASSPATH=$(echo "$CLASSPATH" | sed -e 's/;/;/g')
    fi
fi

# Collect all arguments for the java command
# * Don't append shell variables to avoid bash word splitting
# * Use $@ for arguments, but pass $APP_ARGS to the script
APP_ARGS=""

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options.
eval set -- "$DEFAULT_JVM_OPTS" "$JAVA_OPTS" "$GRADLE_OPTS" "\"-Dorg.gradle.appname=$APP_BASE_NAME\"" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "org.gradle.wrapper.GradleWrapperMain" "$@"

exec "$JAVACMD" "$@"
