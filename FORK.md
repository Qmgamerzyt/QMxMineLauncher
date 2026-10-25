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

## Licence compliance when sharing this build

This fork is distributed under the GNU LGPLv3. If you hand the APK to someone, hand them
the source too — this repository is that source:

    https://github.com/Qmgamerzyt/QMxMineLauncher

The upstream projects are not affiliated with this fork, do not endorse it, and did not
review these changes. Play Minecraft only with an account you own.
