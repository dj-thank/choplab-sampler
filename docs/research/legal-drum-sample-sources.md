# Legal drum one-shot sources

Research date: 2026-08-11. This is a release-engineering source review, not legal advice.

## Decision for the bundled APK

ChopLab bundles deterministic drum synthesis created in this repository. It does not bundle artist recordings, unofficial artist-branded kits, stems, or third-party samples. “Nujabes-like” is treated only as a sound-design direction.

This avoids making a public APK depend on an uploader's rights claim. Creative Commons explains that CC0 permits copying, modification, distribution, and commercial use without asking permission, but a hosting site's label still does not establish that an uploader owned every underlying right.

## Vetted candidates for a future optional sample pack

| Source | Evidence | Release assessment |
|---|---|---|
| [ccMixter Drum Kit Samples](https://ccmixter.org/files/carbonmonoxidemusic/23425) | The item page identifies the uploader, marks the pack CC0, and lists 11 WAV files. | Strong candidate after downloading, inspecting every file, recording the archive SHA-256, and preserving provenance. |
| [OpenGameArt 8-bit Mini Pack](https://opengameart.org/content/sfx-the-ultimate-2017-8-bit-mini-pack) | The official item page marks the archive CC0 and includes bass drum, hi-hat, and snare categories. | Suitable only for an intentionally retro optional palette; it is a mixed SFX pack. |
| [Freesound FAQ](https://freesound.org/help/faq/) and [terms](https://freesound.org/help/tos_web/) | Freesound supports CC0, CC BY, and CC BY-NC, but licenses are uploader-selected and the FAQ warns that uploads may contain material the uploader could not legally license. | Conditional per-file review only. Exclude BY-NC, ambiguous, branded, stem, and artist-recording material. |

## Asset intake checklist

1. Download only from the cited official item page.
2. Record source URL, uploader, license URL, download date, selected filenames, and SHA-256.
3. Inspect archive contents and audio metadata before committing.
4. Keep sample licensing separate from the source-code license.
5. Reject artist recordings, unofficial branded kits, copyrighted stems, and unclear provenance.

Primary license reference: [Creative Commons CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/).
