# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later
#
# kotlinx.serialization reaches the generated serializers reflectively
# only through the companion objects, which R8 keeps once the classes are
# referenced; nothing else in the app relies on reflection.
-keepattributes *Annotation*, InnerClasses
-dontwarn org.slf4j.**
