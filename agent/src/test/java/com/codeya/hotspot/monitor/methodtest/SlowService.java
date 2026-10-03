package com.codeya.hotspot.monitor.methodtest;

/** 方法计时测试目标类：doSlow 自身 sleep 30ms + 调子方法 child（sleep 5ms）。 */
public class SlowService {

    public static final SlowService INSTANCE = new SlowService();

    public void doSlow(int ms) throws Exception {
        Thread.sleep(ms);
        child();
        setValue("x"); // 纯 setter 噪音，应被修剪
        getConfig(2);  // 业务 get 方法（慢且有子调用），应保留
    }

    public void setValue(String v) {
        this.value = v;
    }

    public String getConfig(int ms) throws Exception {
        Thread.sleep(ms); // 真实业务耗时 >=1ms
        child();
        return "cfg";
    }

    private String value;

    public void child() throws Exception {
        Thread.sleep(5);
    }
}
