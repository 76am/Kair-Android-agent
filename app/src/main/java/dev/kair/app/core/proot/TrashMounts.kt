package dev.kair.app.core.proot

/**
 * 回收站在宿主 / 容器两侧的挂载与命名契约。
 *
 * 回收站位于**所有工作区之外**（app 数据目录下的 `trash/`），理由：
 * - 删项目（连带删工作区目录）不会把回收站一起带走；
 * - 各项目共用同一份，用户在一个地方就能找回被删的东西；
 * - 工作区常常就是用户的 git 仓库根，回收站放里面会污染仓库
 *   （未跟踪目录、`git clean -xdf` 会连它一起清掉）。
 *
 * 容器（U78 起只有 proot 一档）把它挂到 [CONTAINER]，并通过 [ENV] 把该路径
 * 交给模型 —— 于是容器里的 `mv ... "$KAIR_TRASH"/` 与用户在面板里看到的是同一个目录。
 */
object TrashMounts {
    /** 容器内挂载点（宿主回收站目录 bind 到这里）。 */
    const val CONTAINER = "/trash"

    /** 容器内指向回收站的环境变量名，由 [ContainerEnv] 注入。 */
    const val ENV = "KAIR_TRASH"
}
