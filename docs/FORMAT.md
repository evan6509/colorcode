# ColorCode format 1 / appearance 1 (Square)

This specification remains unchanged for legacy four-color images. The Android maker now uses [format 2 with five data colors](FORMAT-V2.md); the current reader supports both formats. Version-specific statements below describe format 1.

This is an independent format, not QR, JAB Code or a compatible implementation of either. Coordinates below are module coordinates with a top-left origin. Arrays are row-major. All multibyte integers are unsigned big-endian unless explicitly stated. Only the listed grid sizes are valid.

## Geometry and rendering

Grids are 57, 89 or 129 modules square. There is a four-module opaque white quiet zone outside each edge. Renderer scale is an integer from 1 through 16 pixels per module, with solid cells and no antialiasing. Default export scale is 8. Save as lossless RGB/RGBA PNG; colors are opaque sRGB values:

| Cell value | Color | Bits |
|---:|---|---|
| 0 | `#203c80` blue | `00` |
| 1 | `#008577` teal | `01` |
| 2 | `#d54d45` red | `10` |
| 3 | `#e3b638` yellow | `11` |
| 4 | `#000000` black | structural |
| 5 | `#ffffff` white | structural |

For grid side `n`, reserve four 8×8 corner rectangles whose origins are `(0,0)`, `(n-8,0)`, `(0,n-8)`, `(n-8,n-8)`. Fill them white. In each, draw a 7×7 finder anchored to the outside corner of the symbol: origins `(0,0)`, `(n-7,0)`, `(0,n-7)`, `(n-7,n-7)`. A finder is black on its outermost row/column and central 3×3 square, with the remaining cells white. The inside edge of its 8×8 rectangle is its white separator. Four finders support a projective corner transform.

Reserve timing cells `(x,6)` for `x=8..n-9` and `(6,y)` for `y=8..n-9`. Even varying coordinates are black, odd ones white. Reserve four 4×4 color calibration patches: color `c` covers `x=9+4c..12+4c`, `y=n-4..n-1`, for `c=0..3`.

Every remaining cell carries data or a filler. Enumerate these cells row-major, skipping reservations. The number of available cells is `n² - 256 - 2(n-16) - 64`: 2847, 7455, 16095. Four modules carry one byte (two bits each, most significant pair first). For each enumerated position `(x,y)`, XOR its two-bit value with mask `(x + 2y + ((xy) mod 3)) AND 3`. This fixed mask is reversible and has no compression function.

## Frame

Frames have fixed lengths 615, 1607 and 3500 bytes, respectively. Payload capacities are 599, 1591 and 3484 bytes. The largest capacity is deliberately capped at the previously established 3484-byte format maximum; there is room for a few more bytes in the physical layout, but v1 does not use it.

| Offset | Bytes | Meaning |
|---:|---:|---|
| 0 | 4 | ASCII `CCOD` (`43 43 4f 44`) |
| 4 | 1 | Format version `01` |
| 5 | 1 | Appearance version `01` |
| 6 | 1 | Grid ID: `00`=57, `01`=89, `02`=129 |
| 7 | 1 | Reserved flags, must be `00` |
| 8 | 4 | Payload byte length |
| 12 | 4 | CRC-32/ISO-HDLC over header bytes 0–11 followed by the payload |
| 16 | length | Exact payload bytes |
| remainder | variable | Zero padding to the fixed frame length |

CRC uses the Java/Android `java.util.zip.CRC32` convention (reflected polynomial `0xedb88320`, initial `ffffffff`, final XOR `ffffffff`; empty input CRC `00000000`). CRC is for accidental-error detection, not authentication. No filename, media type, ECI marker, Unicode normalization, compression, base64, external URL indirection or text assumption is implicit. UTF-8 is a maker/reader UI convention; the codec stores raw bytes.

Reject invalid signatures, unknown format/appearance IDs, mismatched grid IDs, nonzero flags, lengths outside the selected grid capacity, incorrect CRC and nonzero frame padding. The 16-byte header and the padding receive the same ECC as payload bytes.

## Error correction and mapping

Split the frame consecutively into blocks of at most 223 data bytes. Encode every block systematically with 32 parity bytes. This is shortened RS(255,223) over GF(256), primitive polynomial `x^8+x^4+x^3+x^2+1` (`0x11d`), primitive element 2 and generator roots `α^0..α^31`. Shortening is equivalent to omitting leading zero data symbols from a full-length block. Empty RS input is not permitted (an empty payload still has a nonempty frame).

| Grid | Data block lengths | Protected stream length |
|---:|---|---:|
| 57 | 223, 223, 169 | 711 bytes |
| 89 | seven × 223, 46 | 1863 bytes |
| 129 | fifteen × 223, 155 | 4012 bytes |

Interleave the encoded blocks column-first: encoded byte 0 from every block in order, then byte 1, continuing to the longest block and skipping exhausted shorter blocks. Map that stream to data modules four cells per byte, high bits first, then apply the mask. Any unfilled modules have unmasked value zero (including the final incomplete group). Those trailing filler modules are not part of the protected stream; decoder ignores them.

Each block corrects up to 16 unknown erroneous **bytes**, regardless of how many bits within those bytes changed, provided marker detection, geometry and color calibration succeeded. Interleaving distributes short spatial bursts across blocks. It does not guarantee recovery of arbitrary large damaged areas. Decoder uses syndromes, Berlekamp–Massey, root search, a small GF Vandermonde solve for error magnitudes, and a second syndrome verification. CRC is checked after correction. Beyond the bound, RS can fail or miscorrect; CRC reduces undetected accidental failures, not to zero. The reported correction count sums corrected codeword bytes, including parity.

## Reader implementation bounds

Input raster must be opaque at sampled symbol pixels, 1–4096 pixels per side, with exactly width×height pixels. Exact framed exports have a direct sampling path. General images are scanned for black/white 1:1:3:1:1 finder runs with vertical cross-checks and clustered centers. Up to ten strong candidates are used for convex four-corner hypotheses. The reader tries known grids, quarter turns and reflections, solves an eight-parameter homography and averages five nearby samples per module.

Structural black/white modules must all pass fixed luminance checks. Color patches must retain chroma, pairwise separation and a bounded distance from the v1 palette. Data colors are assigned by nearest calibrated RGB, then passed through RS/CRC. This models a limited set of color casts, not arbitrary camera white balance, printer gamut or lighting. Multiple symbols and arbitrary scenes are not comprehensively tested. Android downsamples imported images to at most approximately 2048 pixels on the long side, rejects input files larger than 24 MiB and rejects declared dimensions above 200 megapixels before allocating the decoded bitmap.

## Versioning rule

Format version governs framing, mapping, ECC and grid/capacity rules. Appearance version governs rendering and its matching sampler. Square 1 is immutable: never alter its palette, module mapping, marker positions or reserved cells in place. The current encoder and renderer accept only appearance 1; the decoder explicitly rejects unknown IDs.

To introduce an appearance, add a new renderer and matching image-reader candidate, assign a new appearance ID in the protected header, retain the old reader and add cross-version fixtures. A visually different style must still preserve data-module identity and be measurably decodable. A different bit packing, layout, ECC or byte-capacity rule needs a new format version. No future style is claimed compatible until implemented and tested.
