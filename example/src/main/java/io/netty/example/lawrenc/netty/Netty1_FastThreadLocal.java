package io.netty.example.lawrenc.netty;

import io.netty.util.concurrent.FastThreadLocal;
import org.junit.Test;

/**
 * @author : MrLawrenc
 * date  2020/8/9 14:45
 * <p>
 * GC信息可以在pom中关闭  <argLine.printGC>-XX:+PrintGCDetails</argLine.printGC>
 * <p>
 * FastThreadLocal相关源码
 * <p>
 * 无符号右移的规则只记住一点：忽略了符号位扩展，0补最高位  无符号右移运算符>>> 只是对32位和64位的值有意义
 * <p>
 * <p>
 * 字节码计算工具--->JOL Java Object Layout
 * 推荐IDEA插件：https://plugins.jetbrains.com/plugin/10953-jol-java-object-layout
 * jdk工具 http://openjdk.java.net/projects/code-tools/jol/
 * <p>
 * <p>
 * JCTools库 无锁队列
 */
public class Netty1_FastThreadLocal {

    ThreadLocal<Integer> local1 = ThreadLocal.withInitial(() -> 1024);
    ThreadLocal<Integer> local2 = new ThreadLocal<>() {
        @Override
        protected Integer initialValue() {
            return 28;
        }
    };

    /**
     * <pre>
     *     1.如果整个数组都冲突则可能导致死循环（当然概率很小）。会发生?
     *     2.启发式清理为什么会发现了容量会左移（扩大两倍）？
     * </pre>
     */
    @Test
    public void threadLocal() throws Exception {
        System.out.println("local1 init:" + local1.get());
        System.out.println("local2 init:" + local2.get());
        System.out.println("########################################");
        Thread thread = new Thread(() -> {
            local1.set(9527);
            local2.set(2145);
            System.out.println("local1 get:" + local1.get());
            System.out.println("local2 get:" + local2.get());
        });
        thread.start();
        thread.join();

    }


    /**
     * {@link io.netty.microbench.concurrent.FastThreadLocalFastPathBenchmark}
     * {@link io.netty.microbench.concurrent.FastThreadLocalSlowPathBenchmark}
     *
     * @see io.netty.util.concurrent.FastThreadLocalTest
     */
    @Test
    public void fastThreadLocal() {
        FastThreadLocal<String> ftl = new FastThreadLocal<>();
        ftl.set("hello");
        System.out.println(ftl.get());
        ftl.remove();
        System.out.println(ftl.get());
    }
}