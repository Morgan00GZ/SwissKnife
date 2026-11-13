// 「空间回收」的跨进程接口。
//
// 这个接口的实现跑在 Shizuku 拉起的 shell 进程里（uid 2000），
// 所以列表与截断都发生在"有 shell 权限"的那一侧 —— 应用自身 uid 在 Android 11+
// 列不出别人的 Android/data，扫描写在应用侧会永远返回空列表。
//
// 返回值约定（truncate）：
//   >= 0  截断后的实际长度（成功恒为 0）
//   -1    EACCES/EPERM：该文件组位不是 -rw-rw----，重试无用，如实报错
//   -2    ENOENT：路径已消失，调用方重新扫描
//   -9    其它错误
package com.swissknife.clean;

interface ITruncateService {

    /** 服务进程的 uid，用于自证"扫描确实跑在 shell 进程里"（期望 2000）。 */
    int uid();

    /** /sdcard/Android/data 下的包名列表，按字典序。 */
    String[] listPackages();

    /**
     * 全盘模式：递归扫 `/sdcard/Android`（data / obb / media 全在内），
     * 只返回 >= minSize 的文件。每项 "size\tmtime\tabsolutePath"，按 size 降序。
     *
     * 为什么要放在服务进程里：应用自身 uid 在 Android 11+ 列不出别人的
     * `Android/data`，同样的遍历写在应用侧只会得到空列表。
     */
    String[] scanLarge(long minSize);

    /** 指定目录模式：扫 dirPath 下深度不超过 maxDepth 的文件，只返回 >= minSize 的。 */
    String[] scan(String dirPath, int maxDepth, long minSize);

    /** 把文件内容清空为 0 字节（不删目录项）。返回值见文件头。 */
    long truncate(String path);

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