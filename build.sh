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
# Create manifest using a Java helper (handles UTF-8 and 72-byte line wrapping)
MANIFEST_FILE="$BUILD_DIR/MANIFEST.MF"

cat > "$BUILD_DIR/ManifestGen.java" << 'JAVAEOF'
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.io.FileOutputStream;

public class ManifestGen {
    public static void main(String[] args) throws Exception {
        Manifest m = new Manifest();
        Attributes a = m.getMainAttributes();
        a.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        a.putValue("Plugin-Class",
            "org.openstreetmap.josm.plugins.betterime.BetterIMEPlugin");
        a.putValue("Plugin-Description",
            "\u5728\u975e\u6587\u672c\u8f93\u5165\u533a\u57df\u81ea\u52a8"
          + "\u7981\u7528\u4e2d\u6587\u8f93\u5165\u6cd5\uff0c\u9632\u6b62"
          + "\u8f93\u5165\u6cd5\u62e6\u622a JOSM \u5feb\u6377\u952e\u3002"
          + " Auto-disable Chinese IME for non-text components"
          + " to prevent shortcut conflicts.");
        a.putValue("Plugin-Mainversion", "19555");
        a.putValue("Plugin-Version", "1.2.0");
        a.putValue("Plugin-Date", "2026-06-08");
        a.putValue("Plugin-Icon", "images/BetterIME.svg");
        a.putValue("Plugin-Canloadatruntime", "true");
        a.putValue("Plugin-Link", "https://github.com/nj-yzf/josm-BetterIME");
        a.putValue("Author", "nj-yzf");
        try (FileOutputStream fos = new FileOutputStream(args[0])) {
            m.write(fos);
        }
    }
}
JAVAEOF

"$JAVAC" --release 11 -d "$BUILD_DIR" "$BUILD_DIR/ManifestGen.java"
"$JAVA_HOME/bin/java" -cp "$BUILD_DIR" ManifestGen "$MANIFEST_FILE"
rm -f "$BUILD_DIR/ManifestGen.java" "$BUILD_DIR/ManifestGen.class"

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
