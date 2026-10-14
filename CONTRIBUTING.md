# Contributing to ModernOPT

Thank you for considering a contribution! ModernOPT is experimental rendering software, so small, well-tested changes are especially valuable.

## Before you start

- Check existing issues and pull requests to avoid duplicate work.
- For a large feature or architecture change, open an issue first to discuss the design.
- Keep changes focused. Avoid unrelated formatting or refactors in rendering-critical code.
- Never include credentials, private information, or copyrighted material that you do not have permission to contribute.

## Bug reports

Use GitHub Issues and include, when applicable:

- Exact Minecraft, NeoForge, ModernOPT, Sodium, Iris, and other rendering-mod versions
- Operating system, GPU model, and graphics driver version
- Shader pack and resource pack names/versions
- Steps to reproduce, expected behaviour, and actual behaviour
- Relevant log or crash-report excerpts, with personal data and secrets redacted

Do not state that a mod combination is supported solely because it launches once; include the exact tested setup.

## Pull requests

1. Fork the repository and create a branch for your change.
2. Explain the problem and the proposed solution in the pull request.
3. Keep rendering changes easy to review and document any assumptions about framebuffer size, render order, graphics state, and resource lifetime.
4. Build with Java 21 using `gradle --no-daemon clean build`.
5. Test in Minecraft where practical. Describe tests that were not possible.
6. Update English and Russian documentation when user-visible behaviour changes.
7. Add or update `THIRD_PARTY_NOTICES.md` for any third-party code or assets.

## Licensing and provenance

By submitting a contribution, you confirm that you created it or have the necessary rights to submit it. Unless a contribution explicitly states otherwise and the maintainer accepts that arrangement, you agree that your contribution may be distributed as part of ModernOPT under the repository's MIT License. Existing third-party code remains subject to its original licence and notices.

Do not copy code from another project just because it is publicly viewable. Verify the applicable licence, preserve copyright and licence notices, and record the source and changes. If provenance or permission is unclear, do not submit that material until it is resolved.

## Code style and quality

- Follow the surrounding Java style and use descriptive names.
- Prefer clear, defensive behaviour in the rendering path; avoid leaving OpenGL state or resources in an inconsistent state.
- Avoid logging sensitive information.
- Do not claim performance improvements without measurements. Include test conditions and before/after data when making performance claims.

## Review

Maintainers may request changes, decline a contribution that cannot be maintained, or defer features until the rendering core is stable. Please keep discussions constructive and respect the Code of Conduct.
