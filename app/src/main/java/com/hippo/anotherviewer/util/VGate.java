package com.hippo.anotherviewer.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * V 门：图片页文件<b>落盘前</b>的字节级校验（规格：contracts/integrity-vgate.md，自包含）。
 *
 * <p>只做廉价字节级启发式（V1 魔数 + V2 尾标记），不做完整解码、不做 IO、<b>不算哈希</b>。
 *
 * <ul>
 * <li>V1（按内容嗅探魔数，四种互斥，全不命中即拒）先于 V2（精确到流末尾的尾标记），
 *     V1 拒则不看 V2。</li>
 * <li>V2 按格式：JPEG 末 2 字节 {@code FF D9}；PNG 末 8 字节 {@code IEND+CRC}
 *     （{@code 49 45 4E 44 AE 42 60 82}）；GIF 末字节 {@code 3B}；WebP 字节 4–7 的小端 u32
 *     == 总长 − 8（RIFF 容器长度自洽，不区分 VP8/VP8L/VP8X chunk）。</li>
 * <li>已知盲区（规格 §3，有意接受）：垃圾恰好使流末尾仍满足尾规则时放行——V 门是廉价闸门，
 *     不是解码器，此类残留由 SHA-256 基线巡检兜底。</li>
 * </ul>
 *
 * <p>{@link #check(byte[])} 与 {@link #check(InputStream)} 两版共用同一条判定路径：
 * 字节版一次喂入；流式版逐块喂入，边读边定头（≤12B）、滚动保留末尾 ≤8B 判尾，
 * 内存占用 O(12B 头 + 8B 尾 + 8KB 读缓冲)，不要求流可回溯，<b>不关闭</b>调用方的流——
 * Wave 2 下载钩子可用它校验下载流而无需全量驻内存。
 */
public final class VGate {

    /**
     * 内容嗅探命中的图片格式（与扩展名无关）。
     *
     * <p>{@link #minHeaderLen}：V1 命中该格式所需最小长度（contracts/integrity-vgate.md §2 表）。
     * {@link #minTotalLen}：V2 尾窗口不与头重叠所需最小总长（§3 表）——总长不足即 V2 拒。
     */
    public enum Format {
        JPEG(2, 4),
        PNG(8, 16),
        GIF(6, 7),
        WEBP(12, 12);

        /** V1 命中该格式所需最小文件长度。 */
        public final int minHeaderLen;

        /** V2 尾窗口不与头重叠所需最小总长；总长不足即 V2 拒。 */
        public final int minTotalLen;

        Format(int minHeaderLen, int minTotalLen) {
            this.minHeaderLen = minHeaderLen;
            this.minTotalLen = minTotalLen;
        }
    }

    /** 拒因（contracts/integrity-vgate.md §2/§3）。 */
    public enum RejectReason {
        /** 头部不命中任何格式魔数：空文件、HTML 错误页伪装（{@code .jpg} 扩展名无关）、头部截断。 */
        V1_MAGIC,

        /** 尾标记不过：截断、合法图 + 尾部追加垃圾、WebP RIFF 长度不自洽、总长短于尾窗口下限。 */
        V2_TAIL,
    }

    /**
     * V 门判定结果。accept ⇔ V1 ∧ V2；accept 后由调用方对落盘字节计算 SHA-256 基线
     * （V 门本身不算哈希，规格 §4）。
     */
    public static final class Result {
        /** 单例 accept，V 门不为他物分配。 */
        public static final Result ACCEPT = new Result(true, null, null);

        /** true ⇔ V1 ∧ V2 全过。 */
        public final boolean accepted;

        /** 拒因；accept 时为 {@code null}。 */
        public final RejectReason reason;

        /**
         * V1 嗅探出的格式。V1 拒（无格式命中）时为 {@code null}，V2 拒时必非 {@code null}。
         */
        public final Format format;

        private Result(boolean accepted, RejectReason reason, Format format) {
            this.accepted = accepted;
            this.reason = reason;
            this.format = format;
        }

        /** 构造 V2 拒（或带格式的拒）：reason 必非 null。 */
        public static Result reject(RejectReason reason, Format format) {
            return new Result(false, Objects.requireNonNull(reason), format);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Result)) {
                return false;
            }
            Result other = (Result) o;
            return accepted == other.accepted
                    && reason == other.reason
                    && format == other.format;
        }

        @Override
        public int hashCode() {
            return Objects.hash(accepted, reason, format);
        }

        @Override
        public String toString() {
            if (accepted) {
                return "Accept";
            }
            return "Reject(reason=" + reason + ", format=" + format + ")";
        }
    }

    /** 字节版：对完整字节数组做 V 门校验。 */
    public static Result check(byte[] bytes) {
        Engine engine = new Engine();
        engine.update(bytes, 0, bytes.length);
        return engine.finish();
    }

    /**
     * 流式版：把 {@code input} 读到 EOF 并判定（消费整条流，但<b>不关闭</b>它）。
     * 判定语义与字节版完全一致。
     *
     * @throws IOException 流读取失败时原样抛出；不吞 IO 异常。
     */
    public static Result check(InputStream in) throws IOException {
        Engine engine = new Engine();
        byte[] buf = new byte[STREAM_BUFFER];
        while (true) {
            int n = in.read(buf);
            if (n < 0) {
                break;
            }
            if (n > 0) {
                engine.update(buf, 0, n);
            }
        }
        return engine.finish();
    }

    private VGate() {
    }

    // ── 内部：字节级引擎（bytes 版与流式版共用，保证两版判定恒一致） ───────────

    /** V1 嗅探窗口 = 四种魔数最长者（WebP 12B）；V2 滚动尾窗口 = PNG 尾标记 8B。 */
    private static final int HEAD_LEN = 12;
    private static final int TAIL_LEN = 8;
    private static final int STREAM_BUFFER = 8 * 1024;

    private static final byte[] JPEG_SOI = {(byte) 0xFF, (byte) 0xD8};
    private static final byte[] JPEG_EOI = {(byte) 0xFF, (byte) 0xD9};

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
    };

    /** {@code IEND} 类型 + 其固定 CRC {@code AE 42 60 82}（PNG 尾 8 字节）。 */
    private static final byte[] PNG_IEND_TAIL = {
            0x49, 0x45, 0x4E, 0x44,
            (byte) 0xAE, 0x42, 0x60, (byte) 0x82,
    };

    private static final byte[] GIF87A = ascii("GIF87a");
    private static final byte[] GIF89A = ascii("GIF89a");
    private static final byte[] GIF_TRAILER = {0x3B};

    private static final byte[] RIFF = ascii("RIFF");
    private static final byte[] WEBP_TAG = ascii("WEBP");

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 滚动判定引擎：头 12B 定 V1，尾 8B + 总长定 V2。喂完全部字节后调 {@link #finish()}。
     */
    private static final class Engine {
        private final byte[] head = new byte[HEAD_LEN];
        private int headLen;
        private final byte[] tail = new byte[TAIL_LEN]; // 最近读到的 ≤8 字节
        private int tailLen;
        private long total;

        void update(byte[] chunk, int off, int len) {
            if (len <= 0) {
                return;
            }
            int headTake = Math.min(len, HEAD_LEN - headLen);
            if (headTake > 0) {
                System.arraycopy(chunk, off, head, headLen, headTake);
                headLen += headTake;
            }
            if (len >= TAIL_LEN) {
                System.arraycopy(chunk, off + len - TAIL_LEN, tail, 0, TAIL_LEN);
                tailLen = TAIL_LEN;
            } else {
                // 保留旧尾的后 (TAIL_LEN - len) 字节，再接上新块
                int shift = Math.min(tailLen, TAIL_LEN - len);
                if (shift > 0) {
                    System.arraycopy(tail, tailLen - shift, tail, 0, shift);
                }
                System.arraycopy(chunk, off, tail, shift, len);
                tailLen = Math.min(tailLen + len, TAIL_LEN);
            }
            total += len;
        }

        Result finish() {
            Format format = sniff(head, headLen);
            if (format == null) {
                return Result.reject(RejectReason.V1_MAGIC, null);
            }
            if (total < format.minTotalLen) {
                return Result.reject(RejectReason.V2_TAIL, format);
            }
            boolean tailOk;
            switch (format) {
                case JPEG:
                    tailOk = tailEndsWith(JPEG_EOI);
                    break;
                case PNG:
                    tailOk = tailEndsWith(PNG_IEND_TAIL);
                    break;
                case GIF:
                    tailOk = tailEndsWith(GIF_TRAILER);
                    break;
                // 只做 RIFF 容器长度自洽：声明长度（字节 4–7 小端 u32）== 总长 − 8
                case WEBP:
                    tailOk = riffDeclaredLength(head) == total - 8;
                    break;
                default:
                    throw new AssertionError(format);
            }
            return tailOk ? Result.ACCEPT : Result.reject(RejectReason.V2_TAIL, format);
        }

        /** {@code magic} 是否恰为当前已见字节（{@code tail} 的末 {@code tailLen} 字节）的结尾。 */
        private boolean tailEndsWith(byte[] magic) {
            if (tailLen < magic.length) {
                return false;
            }
            for (int i = 0; i < magic.length; i++) {
                if (tail[tailLen - magic.length + i] != magic[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    /** V1：头部魔数嗅探。四种魔数首字节互异，天然互斥，最多命中一种。 */
    private static Format sniff(byte[] head, int headLen) {
        if (matchesAt(head, headLen, 0, JPEG_SOI)) {
            return Format.JPEG;
        }
        if (matchesAt(head, headLen, 0, PNG_SIGNATURE)) {
            return Format.PNG;
        }
        if (matchesAt(head, headLen, 0, GIF87A) || matchesAt(head, headLen, 0, GIF89A)) {
            return Format.GIF;
        }
        if (matchesAt(head, headLen, 0, RIFF) && matchesAt(head, headLen, 8, WEBP_TAG)) {
            return Format.WEBP;
        }
        return null;
    }

    /** {@code head} 的前 {@code available} 字节中，{@code offset} 起是否完整出现 {@code magic}（不足长即不命中）。 */
    private static boolean matchesAt(byte[] head, int available, int offset, byte[] magic) {
        if (available < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (head[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** WebP 字节 4–7 的小端 u32（RIFF 声明的容器长度）。 */
    private static long riffDeclaredLength(byte[] head) {
        return (head[4] & 0xFFL)
                | ((head[5] & 0xFFL) << 8)
                | ((head[6] & 0xFFL) << 16)
                | ((head[7] & 0xFFL) << 24);
    }
}
