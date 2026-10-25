# Fork notice — QMxMine Launcher

This repository is a personal, private-use fork of **MojoLauncher**, which is itself
a fork of **PojavLauncher**.

| | Repository | License |
|---|---|---|
| This fork | `Qmgamerzyt/QMxMineLauncher` | GNU LGPLv3 (inherited) |
| Upstream | [MojoLauncher/MojoLauncher](https://github.com/MojoLauncher/MojoLauncher) @ `734bcb3` | GNU LGPLv3 |
| Origin | [PojavLauncherTeam/PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) | GNU LGPLv3 |

The original `LICENSE` file is preserved unchanged, and the full text of this fork's
origin commit is recorded in the git history.

## Modifications made in this fork

Everything below is in addition to an unmodified checkout of upstream `v3_openjdk`.
Game launch logic, renderers, native code and JNI layers are untouched.

1. **Branding** — user-facing name changed from MJLauncher/MojoLauncher to **QMxMine**
   in `values/strings.xml`, all 51 locale files, `Tools.APP_NAME`, `GameActivity`
   (window title) and `MavenName` (log tag). Internal style identifiers such as
   `@style/MojoLauncher.AppTheme` were left alone because they are not user visible
   and renaming them would churn every layout file.
2. **Icon artwork** — `mipmap-*/ic_launcher*` replaced with new artwork; adaptive-icon
   background colour set to `#0A0F1A`.
3. **Application ID** — `git.artdeell.mjlaunch` → `com.qmgamerzyt.qmxmine`, together with
   the `application_package`, `storageProviderAuthorities` and `group_id` resource values
   that must stay in sync with it. The Java/namespace package `git.artdeell.mojo` was
   deliberately left as-is, since moving it would break the JNI entry points and the
   `targetClass` references for no user-visible benefit.
4. **Version string** — prefix `justicia-` → `qmxmine-`.
5. **CI** — `.github/workflows/android.yml` additionally publishes the debug APK as a
   GitHub Release asset so it can be downloaded without signing in.
6. **Content manager** — the modpack search screen now carries a content kind, so the
   same list searches and installs several sorts of game content next to modpacks:

   | Kind | Modrinth | CurseForge | Installed into |
   |---|---|---|---|
   | Mods | `project_type:mod` | classId 6 | `<instance>/mods` |
   | Resource packs | `project_type:resourcepack` | classId 12 | `<instance>/resourcepacks` |
   | Shaders | `project_type:shader` | classId 6552 | `<instance>/shaderpacks` |
   | Worlds | not hosted by Modrinth | classId 17 | `<instance>/saves` |
   | Modpacks | `project_type:modpack` | classId 4471 | a new profile (unchanged) |

   Added files are `ContentKind` (kind to provider id to folder), `ContentIndex` (a
   record of what each project placed in an instance, so a later install replaces the
   file it succeeded instead of leaving two copies in the same folder) and
   `ContentInstaller` (instance picker, SHA-1 checked download, and world unpacking
   that refuses an archive entry escaping the folder it is unpacked into).

   The search screen gained a tab row for the five kinds and is reachable from a new
   *Browse content* button on the main menu. A world can also be brought in from a
   local `.zip` archive, which matters because Modrinth hosts no worlds at all.

   A *Manage installed* button on the same screen lists what an instance already holds
   and takes an entry away again, working from the same index so it only ever offers
   files the launcher itself placed.

Nothing outside `app_mojo` was taken from another launcher. The content manager was
written against the public Modrinth and CurseForge API documentation and against the
patterns already used in this codebase; no source file was copied from ZalithLauncher,
so this fork stays under the LGPLv3 it inherited.

## Licence compliance when sharing this build

This fork is distributed under the GNU LGPLv3. If you hand the APK to someone, hand them
the source too — this repository is that source:

    https://github.com/Qmgamerzyt/QMxMineLauncher

The upstream projects are not affiliated with this fork, do not endorse it, and did not
review these changes. Play Minecraft only with an account you own.
