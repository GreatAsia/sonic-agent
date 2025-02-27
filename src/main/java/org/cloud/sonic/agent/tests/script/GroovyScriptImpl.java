package org.cloud.sonic.agent.tests.script;

import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.IDevice;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.cloud.sonic.agent.tests.LogUtil;
import org.cloud.sonic.driver.android.AndroidDriver;
import org.cloud.sonic.driver.ios.IOSDriver;

public class GroovyScriptImpl implements GroovyScript {
    @Override
    public void runAndroid(AndroidDriver androidDriver, IDevice iDevice, JSONObject globalParams, LogUtil logUtil, String script) {
        Binding binding = new Binding();
        binding.setVariable("androidDriver", androidDriver);
        binding.setVariable("iDevice", iDevice);
        binding.setVariable("globalParams", globalParams);
        binding.setVariable("logUtil", logUtil);
        GroovyShell gs = new GroovyShell(binding);
        gs.evaluate(script);
    }

    @Override
    public void runIOS(IOSDriver iosDriver, String udId, JSONObject globalParams, LogUtil logUtil, String script) {
        Binding binding = new Binding();
        binding.setVariable("iosDriver", iosDriver);
        binding.setVariable("udId", udId);
        binding.setVariable("globalParams", globalParams);
        binding.setVariable("logUtil", logUtil);
        GroovyShell gs = new GroovyShell(binding);
        gs.evaluate(script);
    }
}
