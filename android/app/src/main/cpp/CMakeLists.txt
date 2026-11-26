# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.22.1)
project(crateaudio C)
add_library(crateaudio SHARED mp3.c flac.c analysis.c)
target_compile_options(crateaudio PRIVATE -O3)
# The meter's sums have to be the Kotlin ones to the bit: no fused
# multiply-add.
set_source_files_properties(analysis.c PROPERTIES COMPILE_OPTIONS -ffp-contract=off)
