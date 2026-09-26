# Changelog

## [0.3.0](https://github.com/MdTanvirHossainTusher/nightshift/compare/v0.2.0...v0.3.0) (2026-09-26)


### Features

* add render blueprint, bind to PORT and reset stale publish branches ([66ad69a](https://github.com/MdTanvirHossainTusher/nightshift/commit/66ad69aa2b85ee1d8849aca28e55103849d71ce4))
* add scan reset capability, update models to luna, gemini and claude, and gitignore pptx ([f4c6102](https://github.com/MdTanvirHossainTusher/nightshift/commit/f4c6102b72c311cb918a584cc9abe59f578599ae))
* add ui provider selector, api key caching, and real github repo integration ([7d1275a](https://github.com/MdTanvirHossainTusher/nightshift/commit/7d1275a58a546708f6def6293442de581e71e3f2))
* embed model selector in header and disable static caching ([303f941](https://github.com/MdTanvirHossainTusher/nightshift/commit/303f9417d3af47d579ab041322a39a148fb5a2d1))


### Bug Fixes

* build patches from model search/replace edits so llm fixes apply cleanly ([7f58eba](https://github.com/MdTanvirHossainTusher/nightshift/commit/7f58eba357929e8281c3ef96f487b6b9f41061fe))
* drop buildkit cache mounts so railway accepts the dockerfile ([0dc8e73](https://github.com/MdTanvirHossainTusher/nightshift/commit/0dc8e7332cb51dc187e1d119e665b83b3c06b6cb))
* prevent transaction rollback on external model failure with graceful fallback ([cf20cb7](https://github.com/MdTanvirHossainTusher/nightshift/commit/cf20cb73b8e912c916a01f6f960ff3cf72003a2c))
* publish real prs from a repo clone and gate patches through verifier revision and brace checks ([3ba8052](https://github.com/MdTanvirHossainTusher/nightshift/commit/3ba805248d09d1d0539e5d0951159c73d9f502e2))

## [0.2.0](https://github.com/MdTanvirHossainTusher/nightshift/compare/v0.1.0...v0.2.0) (2026-09-26)


### Features

* add application config, profiles, and NightshiftProperties ([4a7cc11](https://github.com/MdTanvirHossainTusher/nightshift/commit/4a7cc116d94689898a9123b3915694f32094da52))
* add heuristic client ([f634b7b](https://github.com/MdTanvirHossainTusher/nightshift/commit/f634b7b7ec162defb76b83d4e88adcf53d33bd04))
* add triage agent, secret masking ([8bdd0e8](https://github.com/MdTanvirHossainTusher/nightshift/commit/8bdd0e81dd6c92c95c186372fee2404944b1bc2c))
* configure production deployment and multi-stage container build ([7990172](https://github.com/MdTanvirHossainTusher/nightshift/commit/79901726dd74ea61d7fe82965b87fc82ecc447f7))
* implement code locator over demo target-repo ([917bccf](https://github.com/MdTanvirHossainTusher/nightshift/commit/917bccf13a89a3c00e51c10570468cf264b1aaea))
* implement fix agent and patch guards ([c8b9050](https://github.com/MdTanvirHossainTusher/nightshift/commit/c8b9050db177ad76a2be9b544355c89bed2f9eb7))
* implement mcp server and mcp configuration ([8e663d4](https://github.com/MdTanvirHossainTusher/nightshift/commit/8e663d48bccd93b770b56e060b616bd1bbef464e))
* implement publisher service and pr renderer ([6dafdb4](https://github.com/MdTanvirHossainTusher/nightshift/commit/6dafdb438216c82d181c8305bbe5bfc26704078f))
* implement real llm provider adapters ([5380d03](https://github.com/MdTanvirHossainTusher/nightshift/commit/5380d03f6e0658c35aa2762690a27c2432a24eb6))
* implement rest controllers, dtos, and openapi annotations ([5912c2c](https://github.com/MdTanvirHossainTusher/nightshift/commit/5912c2c9932bf05a266aeae399089f0d202f2fc8))
* implement scan scheduler and single-run guard ([266ab28](https://github.com/MdTanvirHossainTusher/nightshift/commit/266ab28e0d08715fc1adc33c5172c71f312335dd))
* implement static dashboard with four views ([a69f606](https://github.com/MdTanvirHossainTusher/nightshift/commit/a69f606b3979bb2bf7d6cb59318704590ba55cd5))
* implement transactional outbox and email notifier ([7f32589](https://github.com/MdTanvirHossainTusher/nightshift/commit/7f32589b54f3f1dc3e707c6b7907964246ee969d))
* implement verifier agent ([a913089](https://github.com/MdTanvirHossainTusher/nightshift/commit/a913089fa7831d451226b1f448cd4a7d06974ad9))
* **scan:** implement task 3 — incremental reader, parser, fingerprinter, checkpointing ([8b9760c](https://github.com/MdTanvirHossainTusher/nightshift/commit/8b9760c751a3b61d62aee524cf247eb83d688414))


### Bug Fixes

* add executable permissions for gradlew in git, ci, and dockerfile ([abd162a](https://github.com/MdTanvirHossainTusher/nightshift/commit/abd162a26b42166a4459b8b6492af7b4d7a092be))
* auto-register default log sources on startup and scan ([fe3e45d](https://github.com/MdTanvirHossainTusher/nightshift/commit/fe3e45d7b9d3baed5cc390c5d4d7214e39258ba4))


### Documentation

* add presentation slide deck in pptx format ([feb1f46](https://github.com/MdTanvirHossainTusher/nightshift/commit/feb1f4674a100703e5b7cfeae85333549ba7ec4d))
* complete project readme and submission deliverables ([519744e](https://github.com/MdTanvirHossainTusher/nightshift/commit/519744e7470de4d975f4bd77c8ec71d90b950f27))
