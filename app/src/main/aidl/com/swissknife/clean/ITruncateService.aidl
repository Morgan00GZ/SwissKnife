// 「空间回收」的跨进程接口。
//
// 这个接口的实现跑在 Shizuku 拉起的 shell 进程里（uid 2000），
// 所以列表与截断都发生在"有 shell 权限"的那一侧 —— 应用自身 uid 在 Android 11+
// 列不出别人的 Android/data，扫描写在应用侧会永远返回空列表。
//
// 返回值约定（clear）：
//   0     已移除条目（真删除成功）
//   1     已截断为 0 字节（条目保留）
//   -1    EACCES/EPERM：既删不掉也写不进
//   -2    ENOENT：路径已消失，调用方重新扫描
//   -9    其它错误
package com.swissknife.clean;

interface ITruncateService {

    /** 服务进程的 uid，用于自证"扫描确实跑在 shell 进程里"（期望 2000）。 */
    int uid();

    /** /sdcard/Android/data 下的包名列表，按字典序。 */
    String[] listPackages();

    /**
     * 全盘模式：递归扫 `/sdcard/Android`（data / obb / media 全在内）。
     *
     * [cacheDirsOnly] = true 时只产出「缓存类目录」行，并且不进入这些目录内部；
     * false 时只产出普通文件行（cache 目录照常递归，里面的单个大文件也会列出）。
     *
     * 返回两种行，**一次遍历同时产出**（分两次扫要多花一倍时间）：
     *   `size\tmtime\tpath`      普通文件，size >= minSize 才返回
     *   `D\tsize\tcount\tpath`   「缓存类」目录（cache/temp/tmp/files… 见服务端名单），
     *                            size 是递归合计、count 是文件数，同样 >= minSize 才返回
     * 目录行以 `D` 开头，调用方据此区分（size 是数字，D 不是）。
     */
    String[] scanLarge(long minSize, boolean cacheDirsOnly);

    /**
     * 指定目录模式：只扫 dirPath 下、深度不超过 maxDepth 的内容。
     * minSize=0 表示不按大小过滤（"列出所有文件"），条数仍受服务端上限约束。
     */
    String[] scan(String dirPath, int maxDepth, long minSize, boolean cacheDirsOnly);

    /**
     * 清理一个文件：**先尝试真删除（unlink 目录项），删不掉再把内容截断为 0**。
     *
     * 为什么不能全局写死"只能截断"：目录组位各应用不同 —— `drwxrws---`(2770) 组内有 `w`，
     * shell 在 ext_data_rw 组里就能 unlink；`drwxr-s---`(2750) 组内只读，就只能截断。
     * 同一台机器上两种目录同时存在，所以只能逐个文件试。
     *
     * 返回值：
     *   0     已移除（条目消失，空间立刻回收）
     *   1     已截断为空壳（条目保留，大小 0）
     *   -1    EACCES/EPERM：既删不掉也写不进，重试无用
     *   -2    ENOENT：路径已消失，调用方重新扫描
     *   -9    其它错误
     */
    long clear(String path);

    /**
     * 清空一个「缓存类」目录的内容，**保留目录本身**。
     *
     * 为什么保留目录：空目录只占 4 KB，回收的空间几乎一样；而某些应用不会重建目录，
     * 直接删掉可能让它下次启动报错（2026-11-13 用户拍板：清空内容、保留空目录）。
     *
     * 服务端会再校验一次目录名（必须是 cache/temp/tmp/files… 名单内），
     * 防止调用方传错路径把整个应用数据端掉 —— 本方法以 shell 身份执行，必须自己兜底。
     *
     * 返回：0=全部清空成功，-1=有内容没删掉（残留），-3=目录不存在，-4=目录名不在名单内
     */
    long clearDir(String path);

    /** 目标包是否还有存活进程；有则拒绝截断。 */
    boolean isProcessAlive(String pkg);

    /**
     * 让服务进程自己退出。页面销毁时必须调一次。
     *
     * 为什么不用 unbindUserService(remove=true)：本机 Shizuku 上它只解了 binder，
     * app_process 仍然常驻（PPID=1），每进一次页面就多留一个 shell 身份的进程。
     * 实测累积到 11 个，所以改成服务侧自杀。
     */
    void shutdown();
}