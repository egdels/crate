#!/bin/sh
# Builds every Linux package of the prototype for the machine it runs on
# (x86_64 or arm64) into linux/build/dist:
#
#   crate_<version>-1_<arch>.deb      Debian, Ubuntu
#   crate-<version>-1.<arch>.rpm      Fedora, openSUSE
#   Crate-<version>-<arch>.AppImage   any distribution
#   crate-<version>-linux-<arch>.tar.gz   the plain folder, bin/Crate starts it
#   crate-<version>-linux-<arch>.jar  needs a Java 17 on the machine
#
# All but the jar carry their own Java runtime; all of them need GStreamer
# 1.16 or later (good, bad, libav) on the machine. The .deb and the .rpm
# name it as a dependency, the others cannot; the app says what is missing
# when it starts without. Runs on Linux only: needs a JDK 17 (JAVA_HOME or
# the PATH) with jpackage, dpkg-deb and fakeroot, rpmbuild, and, for the
# AppImage, appimagetool on the PATH or the network to fetch it.
set -eu
cd "$(dirname "$0")/../linux"
VERSION=$(sed -n 's/^appVersion=//p' ../android/gradle.properties)
case "$(uname -m)" in
    x86_64) ARCH=x86_64 ;;
    aarch64|arm64) ARCH=aarch64 ;;
    *) echo "unsupported machine: $(uname -m)" >&2; exit 1 ;;
esac

# Nothing from an earlier run, or two jars answer to one pattern below.
rm -rf app/build/compose
./gradlew :app:packageUberJarForCurrentOS :app:createDistributable

BIN=app/build/compose/binaries/main
DIST=build/dist
rm -rf "$DIST"
mkdir -p "$DIST"

# The .deb and the .rpm: jpackage wraps the folder Gradle just made. Called
# here and not through Compose's packageDeb/packageRpm, whose DSL has no
# field for dependencies. The .rpm's are alternatives, since Fedora and
# openSUSE name the same packages differently.
DEB_DEPS="libgstreamer1.0-0 (>= 1.16), gstreamer1.0-plugins-base, gstreamer1.0-plugins-good, gstreamer1.0-plugins-bad, gstreamer1.0-libav"
RPM_DEPS="(gstreamer1-plugins-base or gstreamer-plugins-base), (gstreamer1-plugins-good or gstreamer-plugins-good), (gstreamer1-plugins-bad-free or gstreamer-plugins-bad), (gstreamer1-plugin-libav or gstreamer1-libav or gstreamer-plugins-libav)"
package() { # type, dependencies, further options
    type=$1 deps=$2
    shift 2
    "${JAVA_HOME:+$JAVA_HOME/bin/}jpackage" --type "$type" --dest "$DIST" \
        --app-image "$BIN/app/Crate" --name Crate --app-version "$VERSION" \
        --description "Music player for a folder of albums" --vendor Crate \
        --license-file ../LICENSE --icon ../Packaging/AppIcon-256.png \
        --linux-package-name crate --linux-shortcut \
        --linux-menu-group "AudioVideo;Audio;Player" --linux-app-category sound \
        --linux-rpm-license-type GPL-3.0-or-later --linux-package-deps "$deps" "$@"
}
# The address is the .deb's Maintainer field, shown as "Crate <address>".
package deb "$DEB_DEPS" --linux-deb-maintainer Christian.Kierdorf@schliweb.de
package rpm "$RPM_DEPS"
cp app/build/compose/jars/*.jar "$DIST/crate-$VERSION-linux-$ARCH.jar"
tar -czf "$DIST/crate-$VERSION-linux-$ARCH.tar.gz" -C "$BIN/app" Crate

# The AppImage: the same folder with a start script, a menu entry and the icon.
APPDIR=build/AppDir
rm -rf "$APPDIR"
mkdir -p "$APPDIR"
cp -R "$BIN/app/Crate/." "$APPDIR/"
cp ../Packaging/AppIcon-256.png "$APPDIR/crate.png"
cat > "$APPDIR/crate.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Crate
Comment=Music player for a folder of albums
Exec=Crate
Icon=crate
Terminal=false
Categories=AudioVideo;Audio;Player;
StartupWMClass=crate-Crate
DESKTOP
cat > "$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
exec "$(dirname "$(readlink -f "$0")")/bin/Crate" "$@"
APPRUN
chmod +x "$APPDIR/AppRun"
TOOL=$(command -v appimagetool || true)
if [ -z "$TOOL" ]; then
    TOOL=build/appimagetool
    [ -x "$TOOL" ] || {
        curl -fsSL -o "$TOOL" "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$ARCH.AppImage"
        chmod +x "$TOOL"
    }
fi
# The tool is an AppImage itself; unpacked on the fly it needs no FUSE.
ARCH=$ARCH APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream "$APPDIR" "$DIST/Crate-$VERSION-$ARCH.AppImage"

ls -l "$DIST"
