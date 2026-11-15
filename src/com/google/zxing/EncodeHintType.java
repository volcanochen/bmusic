/*
 * Copyright 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing;

/** Encoding hints used by the vendored ZXing QR encoder subset. */
public enum EncodeHintType {
    ERROR_CORRECTION,
    CHARACTER_SET,
    DATA_MATRIX_SHAPE,
    MIN_SIZE,
    MAX_SIZE,
    MARGIN,
    PDF417_COMPACT,
    PDF417_COMPACTION,
    PDF417_DIMENSIONS,
    AZTEC_LAYERS,
    QR_VERSION,
    QR_MASK_PATTERN,
    GS1_FORMAT,
    GS
}
