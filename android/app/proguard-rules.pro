# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later
#
# kotlinx.serialization reaches the generated serializers reflectively
# only through the companion objects, which R8 keeps once the classes are
# referenced; nothing else in the app relies on reflection.
-keepattributes *Annotation*, InnerClasses
-dontwarn org.slf4j.**

# Crate plays local files only and has no INTERNET permission, but Media3
# keeps DefaultHttpDataSource reachable through defaults the app never
# uses, and the Play Console flags that as "downloads and decodes
# images". PlaybackService passes local-only data sources and its own
# BitmapLoader; these rules tell R8 the remaining defaults are dead:
# - MediaSession.Builder: the app always sets a bitmap loader.
# - MediaController.Builder: the loader is only used for legacy
#   (pre-Media3) sessions; Crate's controllers talk to its own session.
# - No media item carries a DRM configuration, so the DRM license
#   fetcher (HTTP) is never created.
-assumevalues class androidx.media3.session.MediaSession$BuilderBase { androidx.media3.common.util.BitmapLoader bitmapLoader return _NONNULL_; }
-assumevalues class androidx.media3.session.MediaController$Builder { androidx.media3.common.util.BitmapLoader bitmapLoader return _NONNULL_; }
-assumevalues class androidx.media3.common.MediaItem$LocalConfiguration { androidx.media3.common.MediaItem$DrmConfiguration drmConfiguration return null; }
