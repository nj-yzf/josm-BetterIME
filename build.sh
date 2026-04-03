#!/bin/bash
# build.sh — Build script for BetterIME JOSM plugin
# Usage: ./build.sh

set -e

# Paths
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME="$SCRIPT_DIR/jdk/jdk-21.0.10+7"
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"

SRC_DIR="$SCRIPT_DIR/src/main/java"
RES_DIR="$SCRIPT_DIR/src/main/resources"
BUILD_DIR="$SCRIPT_DIR/build/classes"
DIST_DIR="$SCRIPT_DIR/dist"
JOSM_JAR="$SCRIPT_DIR/lib/josm-tested.jar"

PLUGIN_NAME="BetterIME"

echo "=== BetterIME Plugin Build ==="
echo "Java: $($JAVAC -version 2>&1)"
echo ""

# Clean
rm -rf "$BUILD_DIR" "$DIST_DIR"
mkdir -p "$BUILD_DIR" "$DIST_DIR"

# Compile
echo "[1/3] Compiling..."
"$JAVAC" \
    --release 11 \
    -cp "$JOSM_JAR" \
    -d "$BUILD_DIR" \
    $(find "$SRC_DIR" -name "*.java")

echo "      Compiled successfully."

# Copy resources
echo "[2/3] Copying resources..."
if [ -d "$RES_DIR" ]; then
    cp -r "$RES_DIR"/* "$BUILD_DIR"/
fi

# Create manifest
MANIFEST_FILE="$BUILD_DIR/MANIFEST.MF"
cat > "$MANIFEST_FILE" << 'MANIFEST'
Manifest-Version: 1.0
Plugin-Class: org.openstreetmap.josm.plugins.betterime.BetterIMEPlugin
Plugin-Description: Auto-disable Chinese IME for non-text components to prevent shortcut conflicts.
Plugin-Mainversion: 19555
Plugin-Version: 1.0.0
Plugin-Date: 2026-04-03
Plugin-Icon: images/BetterIME.svg
Plugin-Canloadatruntime: true
Author: nj-yzf

MANIFEST

# Package JAR
echo "[3/3] Packaging JAR..."
"$JAR" cfm "$DIST_DIR/$PLUGIN_NAME.jar" "$MANIFEST_FILE" -C "$BUILD_DIR" .

echo ""
echo "=== Build Complete ==="
echo "Output: $DIST_DIR/$PLUGIN_NAME.jar"
echo ""

# Show JAR contents
echo "JAR contents:"
"$JAR" tf "$DIST_DIR/$PLUGIN_NAME.jar" | grep -v '/$'

echo ""
echo "To install: copy $DIST_DIR/$PLUGIN_NAME.jar to your JOSM plugins directory"
echo "  Windows: %APPDATA%\\JOSM\\plugins\\"
echo "  Linux:   ~/.local/share/JOSM/plugins/"
echo "  macOS:   ~/Library/JOSM/plugins/"
