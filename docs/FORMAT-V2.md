# ColorCode format 2 / appearance 1 (five-color Square)

Format 2 adds magenta as a fifth data color. The Android maker exports this format by default. The reader accepts both formats; format 1 retains its original palette, mapping and capacities. Older readers cannot read format 2. This remains an independent format requiring the custom reader.

Everything in [format 1](FORMAT.md) remains the same except the changes specified here. The protected header's format byte at offset 4 is `02`; appearance remains `01`. Framing, CRC, grid IDs, Reed–Solomon parameters, interleaving, finders, timing patterns, quiet zone and renderer scales are unchanged.

## Palette and geometry

Data digits are blue `#203c80` (0), teal `#008577` (1), red `#d54d45` (2), yellow `#e3b638` (3), and magenta `#cc33cc` (4). In the in-memory cell array, magenta uses cell value **6**, preserving structural black at 4 and white at 5. Data digits and in-memory cell values are distinct.

There are five calibration patches at `x=9+4c..12+4c`, `y=n-4..n-1`, for digits `c=0..4`. This reserves 80 calibration modules. Remaining data positions are enumerated row-major, as in format 1.

## Packing and capacities

Take pairs of interleaved stream bytes as unsigned big-endian 16-bit words. Express each word as exactly seven base-5 digits, most significant digit first, including leading zeros. If the stream has an odd length, append a zero low byte to its final word for mapping only; it is not part of the ECC stream. Seven digits can represent 78,125 values, enough for every 16-bit word.

For each position `(x,y)`, add mask `(x + 2y + ((xy) mod 3)) mod 5` to its digit modulo 5. All remaining positions use unmasked digit zero. To read, subtract the mask modulo 5 and reconstruct each word. A damaged group whose value exceeds 65,535 contributes its low 16 bits as two suspect bytes for RS correction rather than failing before ECC. Trailing mapping padding and filler are ignored. The protected header, payload CRC and frame padding are checked after correction.

| Grid | Data modules | Maximum stream budget | Frame bytes | Payload bytes | Actual protected stream |
|---:|---:|---:|---:|---:|---:|
| 57 | 2,831 | 808 | 680 | 664 | 808 |
| 89 | 7,439 | 2,124 | 1,836 | 1,820 | 2,124 |
| 129 | 16,079 | 4,594 | 4,014 | 3,998 | 4,590 |

The budget is `floor(data modules / 7) * 2`. Select the largest frame whose length plus `32 * ceil(length / 223)` fits that budget. Subtract the 16-byte header for payload capacity. An altered base-5 group can damage two RS bytes; the existing 16-byte correction bound per block still applies. Increased digital capacity does not establish camera or print reliability.

## API and verification

The core's existing default `encode`/`capacity` calls continue to use format 1. Pass `formatVersion = ColorCode.FIVE_COLOR_VERSION` to encode five-color symbols or calculate their capacity. `Symbol.formatVersion` selects the renderer palette; image reading tries both known formats and verifies that the protected format byte matches the sampled candidate. Unknown formats and appearances are rejected.

Automated coverage includes maximum-capacity PNG round trips at all grids, empty and binary payloads, random payloads, independent base-5 unpacking, corrected damaged groups, color casts, rotations/reflections and the existing synthetic image-reader checks for both formats. A hash captured from the original encoder pins the format 1 cell mapping. Device and printer behavior still require testing.
