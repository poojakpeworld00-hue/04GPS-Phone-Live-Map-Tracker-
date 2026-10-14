#!/bin/bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd "$(dirname "$0")"
./gradlew ${CLEAN:+:app:clean :launcher:clean} :app:assembleDebug :app:testDebugUnitTest :launcher:testDebugUnitTest -q --console=plain 2>&1 | grep -E "^e: |error:|FAILED|What went wrong|Unresolved|> " | head -60
echo "GRADLE_EXIT=${PIPESTATUS[0]}"
