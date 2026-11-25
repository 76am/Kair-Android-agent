package dev.kair.app.core.proot

/**
 * 容器镜像预设：一处选择，三处派生（apt 仓库 / pip index / rootfs 下载）。
 *
 * - **apt base 按架构分开**：arm64 的 Ubuntu 包在 `ubuntu-ports`，x86_64 在 `ubuntu`
 *   （`archive.ubuntu.com` 的 arm64 索引是 404）。用错一个，`apt-get update` 立即满屏 404。
 * - **apt 走 http**：Ubuntu base 镜像不自带 CA 证书，首轮 `apt-get update` 用 https 会直接
 *   败在 TLS；包完整性由 apt 的 gpgv + Ubuntu keyring 保证（与官方默认源一致）。
 * - **rootfs 镜像走 https**：下载由 App 的 OkHttp 完成，证书正常；站点路径是
 *   `<imageBase>/releases/jammy/release/ubuntu-base-22.04.5-base-<arch>.tar.gz`。
 *
 * 只放 id 与 URL —— **不放中文显示名、不 import `R`**：core 不得依赖 Android framework，
 * 显示名由 UI 侧映射到 `strings.xml`（见 `ProotRootfsUi`）。
 */
enum class ContainerMirror(
    val id: String,
    /** apt 仓库 base（arm64，`ubuntu-ports`）。 */
    private val aptBaseArm64: String,
    /** apt 仓库 base（x86_64，`ubuntu`）。 */
    private val aptBaseAmd64: String,
    /** ubuntu-base 镜像 base（不含 `releases/...` 路径）。 */
    val imageBase: String,
    /** pip 的 `index-url`。 */
    val pipIndex: String,
) {
    ALIYUN(
        id = "aliyun",
        aptBaseArm64 = "http://mirrors.aliyun.com/ubuntu-ports",
        aptBaseAmd64 = "http://mirrors.aliyun.com/ubuntu",
        imageBase = "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base",
        pipIndex = "https://mirrors.aliyun.com/pypi/simple/",
    ),
    TUNA(
        id = "tuna",
        aptBaseArm64 = "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
        aptBaseAmd64 = "http://mirrors.tuna.tsinghua.edu.cn/ubuntu",
        imageBase = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base",
        pipIndex = "https://pypi.tuna.tsinghua.edu.cn/simple",
    ),
    USTC(
        id = "ustc",
        aptBaseArm64 = "http://mirrors.ustc.edu.cn/ubuntu-ports",
        aptBaseAmd64 = "http://mirrors.ustc.edu.cn/ubuntu",
        // ubuntu-cdimage 在 USTC 可能 403；镜像拿不到时由 RootfsManager 回退官方 cdimage。
        imageBase = "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base",
        pipIndex = "https://pypi.mirrors.ustc.edu.cn/simple",
    ),
    HUAWEI(
        id = "huawei",
        aptBaseArm64 = "http://repo.huaweicloud.com/ubuntu-ports",
        aptBaseAmd64 = "http://repo.huaweicloud.com/ubuntu",
        imageBase = "https://repo.huaweicloud.com/ubuntu-cdimage/ubuntu-base",
        pipIndex = "https://mirrors.huaweicloud.com/repository/pypi/simple",
    ),
    OFFICIAL(
        id = "official",
        aptBaseArm64 = "http://ports.ubuntu.com/ubuntu-ports",
        aptBaseAmd64 = "http://archive.ubuntu.com/ubuntu",
        imageBase = "https://cdimage.ubuntu.com/ubuntu-base",
        pipIndex = "https://pypi.org/simple",
    ),
    ;

    /** 按 Ubuntu 架构名（`arm64` / `amd64`）取 apt base。 */
    fun aptBase(arch: String): String = if (arch == "arm64") aptBaseArm64 else aptBaseAmd64

    companion object {
        /** 默认预设：国内直连快；Phase 0 已验证 apt 索引与 ubuntu-base 镜像两处都可用。 */
        val DEFAULT: ContainerMirror = ALIYUN

        /** 未知 / 空 id 回退默认预设（旧设置、手改 prefs 都不该让容器断网）。 */
        fun fromId(id: String?): ContainerMirror =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
