# Source this file from the repository root: source scripts/android-env.sh
export JAVA_HOME="$PWD/.android-toolchain/jdk"
export ANDROID_HOME="$PWD/.android-toolchain/sdk"
export GRADLE_USER_HOME="$PWD/.android-toolchain/gradle"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
