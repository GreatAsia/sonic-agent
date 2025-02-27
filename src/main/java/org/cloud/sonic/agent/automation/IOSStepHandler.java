/*
 *  Copyright (C) [SonicCloudOrg] Sonic Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.cloud.sonic.agent.automation;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.cloud.sonic.agent.bridge.ios.SibTool;
import org.cloud.sonic.agent.common.enums.ConditionEnum;
import org.cloud.sonic.agent.common.enums.SonicEnum;
import org.cloud.sonic.agent.common.interfaces.ErrorType;
import org.cloud.sonic.agent.common.interfaces.ResultDetailStatus;
import org.cloud.sonic.agent.common.interfaces.StepType;
import org.cloud.sonic.agent.common.maps.IOSInfoMap;
import org.cloud.sonic.agent.common.maps.IOSProcessMap;
import org.cloud.sonic.agent.common.models.HandleDes;
import org.cloud.sonic.agent.tests.LogUtil;
import org.cloud.sonic.agent.tests.common.RunStepThread;
import org.cloud.sonic.agent.tests.handlers.StepHandlers;
import org.cloud.sonic.agent.tests.script.GroovyScript;
import org.cloud.sonic.agent.tests.script.GroovyScriptImpl;
import org.cloud.sonic.agent.tools.SpringTool;
import org.cloud.sonic.agent.tools.file.DownloadTool;
import org.cloud.sonic.agent.tools.file.UploadTools;
import org.cloud.sonic.driver.common.enums.PasteboardType;
import org.cloud.sonic.driver.common.models.WindowSize;
import org.cloud.sonic.driver.common.tool.SonicRespException;
import org.cloud.sonic.driver.ios.IOSDriver;
import org.cloud.sonic.driver.ios.enums.IOSSelector;
import org.cloud.sonic.driver.ios.enums.SystemButton;
import org.cloud.sonic.driver.ios.service.IOSElement;
import org.cloud.sonic.vision.cv.AKAZEFinder;
import org.cloud.sonic.vision.cv.SIFTFinder;
import org.cloud.sonic.vision.cv.SimilarityChecker;
import org.cloud.sonic.vision.cv.TemMatcher;
import org.cloud.sonic.vision.models.FindResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.testng.Assert;

import javax.imageio.stream.FileImageOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

import static org.testng.Assert.*;

/**
 * @author ZhouYiXun
 * @des iOS自动化处理类
 * @date 2021/8/16 20:10
 */
public class IOSStepHandler {
    public LogUtil log = new LogUtil();
    private IOSDriver iosDriver;
    private JSONObject globalParams = new JSONObject();
    private String udId = "";

    private int status = ResultDetailStatus.PASS;

    public LogUtil getLog() {
        return log;
    }

    public void setTestMode(int caseId, int resultId, String udId, String type, String sessionId) {
        log.caseId = caseId;
        log.resultId = resultId;
        log.udId = udId;
        log.type = type;
        log.sessionId = sessionId;
    }

    public void setGlobalParams(JSONObject jsonObject) {
        globalParams = jsonObject;
    }

    public void startIOSDriver(String udId, int wdaPort) throws Exception {
        this.udId = udId;
        try {
            iosDriver = new IOSDriver("http://127.0.0.1:" + wdaPort);
            iosDriver.disableLog();
            log.sendStepLog(StepType.PASS, "连接设备驱动成功", "");
        } catch (Exception e) {
            log.sendStepLog(StepType.ERROR, "连接设备驱动失败！", "");
            setResultDetailStatus(ResultDetailStatus.FAIL);
            throw e;
        }
        WindowSize windowSize = iosDriver.getWindowSize();
        JSONObject appiumSettings = new JSONObject();
        appiumSettings.put("snapshotMaxDepth", 30);
        appiumSettings(appiumSettings);
        IOSInfoMap.getSizeMap().put(udId, windowSize.getWidth() + "x" + windowSize.getHeight());
    }

    public void closeIOSDriver() {
        try {
            if (iosDriver != null) {
                iosDriver.closeDriver();
                log.sendStepLog(StepType.PASS, "退出连接设备", "");
            }
        } catch (Exception e) {
            log.sendStepLog(StepType.WARN, "测试终止异常！请检查设备连接状态", "");
            //测试异常
            setResultDetailStatus(ResultDetailStatus.WARN);
            e.printStackTrace();
        } finally {
            if (IOSProcessMap.getMap().get(udId) != null) {
                List<Process> processList = IOSProcessMap.getMap().get(udId);
                for (Process p : processList) {
                    if (p != null) {
                        p.children().forEach(ProcessHandle::destroy);
                        p.destroy();
                    }
                }
                IOSProcessMap.getMap().remove(udId);
            }
        }
    }

    public void appiumSettings(JSONObject jsonObject) throws SonicRespException {
        iosDriver.setAppiumSettings(jsonObject);
    }

    public void waitDevice(int waitCount) {
        log.sendStepLog(StepType.INFO, "设备非空闲状态！第" + waitCount + "次等待连接...", "");
    }

    public void waitDeviceTimeOut() {
        log.sendStepLog(StepType.ERROR, "等待设备超时！测试跳过！", "");
        //测试标记为异常
        setResultDetailStatus(ResultDetailStatus.WARN);
    }

    public IOSDriver getDriver() {
        return iosDriver;
    }

    public void setResultDetailStatus(int status) {
        if (status > this.status) {
            this.status = status;
        }
    }

    public void sendStatus() {
        log.sendStatusLog(status);
    }

    //判断有无出错
    public int getStatus() {
        return status;
    }

    //调试每次重设状态
    public void resetResultDetailStatus() {
        status = 1;
    }

    public boolean getBattery() {
        int battery = SibTool.battery(udId);
        if (battery <= 10) {
            log.sendStepLog(StepType.ERROR, "设备电量过低!", "跳过本次测试...");
            return true;
        } else {
            return false;
        }
    }

    private int xpathId = 1;

    public JSONArray getResource() {
        try {
            JSONArray elementList = new JSONArray();
            Document doc = Jsoup.parse(iosDriver.getPageSource());
            String xpath = "";
            elementList.addAll(getChild(doc.body().children(), xpath));
            xpathId = 1;
            return elementList;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public JSONArray getChild(org.jsoup.select.Elements elements, String xpath) {
        JSONArray elementList = new JSONArray();
        for (int i = 0; i < elements.size(); i++) {
            JSONObject ele = new JSONObject();
            int tagCount = 0;
            int siblingIndex = 0;
            String indexXpath;
            for (int j = 0; j < elements.size(); j++) {
                if (elements.get(j).attr("type").equals(elements.get(i).attr("type"))) {
                    tagCount++;
                }
                if (i == j) {
                    siblingIndex = tagCount;
                }
            }
            if (tagCount == 1) {
                indexXpath = xpath + "/" + elements.get(i).attr("type");
            } else {
                indexXpath = xpath + "/" + elements.get(i).attr("type") + "[" + siblingIndex + "]";
            }
            ele.put("id", xpathId);
            xpathId++;
            ele.put("label", "<" + elements.get(i).attr("type") + ">");
            JSONObject detail = new JSONObject();
            detail.put("xpath", indexXpath);
            for (Attribute attr : elements.get(i).attributes()) {
                detail.put(attr.getKey(), attr.getValue());
            }
            ele.put("detail", detail);
            if (elements.get(i).children().size() > 0) {
                ele.put("children", getChild(elements.get(i).children(), indexXpath));
            }
            elementList.add(ele);
        }
        return elementList;
    }

//    public void startRecord() {
//        try {
//            IOSStartScreenRecordingOptions recordOption = new IOSStartScreenRecordingOptions();
//            recordOption.withTimeLimit(Duration.ofMinutes(30));
//            recordOption.withVideoQuality(IOSStartScreenRecordingOptions.VideoQuality.LOW);
//            recordOption.enableForcedRestart();
//            recordOption.withFps(20);
//            recordOption.withVideoType("h264");
//            iosDriver.startRecordingScreen(recordOption);
//        } catch (Exception e) {
//            log.sendRecordLog(false, "", "");
//        }
//    }
//
//    public void stopRecord() {
//        File recordDir = new File("./test-output/record");
//        if (!recordDir.exists()) {//判断文件目录是否存在
//            recordDir.mkdirs();
//        }
//        long timeMillis = Calendar.getInstance().getTimeInMillis();
//        String fileName = timeMillis + "_" + udId.substring(0, 4) + ".mp4";
//        File uploadFile = new File(recordDir + File.separator + fileName);
//        try {
//            synchronized (IOSStepHandler.class) {
//                FileOutputStream fileOutputStream = new FileOutputStream(uploadFile);
//                byte[] bytes = Base64Utils.decodeFromString((iosDriver.stopRecordingScreen()));
//                fileOutputStream.write(bytes);
//                fileOutputStream.close();
//            }
//            log.sendRecordLog(true, fileName, UploadTools.uploadPatchRecord(uploadFile));
//        } catch (Exception e) {
//            log.sendRecordLog(false, fileName, "");
//        }
//    }

    public void install(HandleDes handleDes, String path) {
        handleDes.setStepDes("安装应用");
        path = TextHandler.replaceTrans(path, globalParams);
        handleDes.setDetail("App安装路径： " + path);
        try {
            SibTool.install(udId, path);
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void uninstall(HandleDes handleDes, String appPackage) {
        handleDes.setStepDes("卸载应用");
        appPackage = TextHandler.replaceTrans(appPackage, globalParams);
        handleDes.setDetail("App包名： " + appPackage);
        try {
            SibTool.uninstall(udId, appPackage);
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void terminate(HandleDes handleDes, String packageName) {
        handleDes.setStepDes("终止应用");
        packageName = TextHandler.replaceTrans(packageName, globalParams);
        handleDes.setDetail("应用包名： " + packageName);
        try {
            iosDriver.appTerminate(packageName);
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void runBackground(HandleDes handleDes, long time) {
        handleDes.setStepDes("后台运行应用");
        handleDes.setDetail("后台运行App " + time + " ms");
        try {
            iosDriver.appRunBackground((int) (time / 1000));
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void openApp(HandleDes handleDes, String appPackage) {
        handleDes.setStepDes("打开应用");
        appPackage = TextHandler.replaceTrans(appPackage, globalParams);
        handleDes.setDetail("App包名： " + appPackage);
        try {
            iosDriver.appActivate(appPackage);
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void lock(HandleDes handleDes) {
        handleDes.setStepDes("锁定屏幕");
        handleDes.setDetail("");
        try {
            iosDriver.lock();
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void unLock(HandleDes handleDes) {
        handleDes.setStepDes("解锁屏幕");
        handleDes.setDetail("");
        try {
            iosDriver.unlock();
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void asserts(HandleDes handleDes, String actual, String expect, String type) {
        handleDes.setDetail("真实值： " + actual + " 期望值： " + expect);
        handleDes.setStepDes("");
        try {
            switch (type) {
                case "assertEquals":
                    handleDes.setStepDes("断言验证(相等)");
                    assertEquals(actual, expect);
                    break;
                case "assertTrue":
                    handleDes.setStepDes("断言验证(包含)");
                    assertTrue(actual.contains(expect));
                    break;
                case "assertNotTrue":
                    handleDes.setStepDes("断言验证(不包含)");
                    assertFalse(actual.contains(expect));
                    break;
            }
        } catch (AssertionError e) {
            handleDes.setE(e);
        }
    }

    public String getText(HandleDes handleDes, String des, String selector, String pathValue) {
        String s = "";
        handleDes.setStepDes("获取" + des + "文本");
        handleDes.setDetail("获取" + selector + ":" + pathValue + "文本");
        try {
            s = findEle(selector, pathValue).getText();
            log.sendStepLog(StepType.INFO, "", "文本获取结果: " + s);
        } catch (Exception e) {
            handleDes.setE(e);
        }
        return s;
    }

//    public void hideKey(HandleDes handleDes) {
//        handleDes.setStepDes("隐藏键盘");
//        handleDes.setDetail("隐藏弹出键盘");
//        try {
//            iosDriver.hideKeyboard();
//        } catch (Exception e) {
//            handleDes.setE(e);
//        }
//    }

    public void click(HandleDes handleDes, String des, String selector, String pathValue) {
        handleDes.setStepDes("点击" + des);
        handleDes.setDetail("点击" + selector + ": " + pathValue);
        try {
            findEle(selector, pathValue).click();
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void sendKeys(HandleDes handleDes, String des, String selector, String pathValue, String keys) {
        keys = TextHandler.replaceTrans(keys, globalParams);
        handleDes.setStepDes("对" + des + "输入内容");
        handleDes.setDetail("对" + selector + ": " + pathValue + " 输入: " + keys);
        try {
            findEle(selector, pathValue).sendKeys(keys);
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void getTextAndAssert(HandleDes handleDes, String des, String selector, String pathValue, String expect) {
        handleDes.setStepDes("获取" + des + "文本");
        handleDes.setDetail("获取" + selector + ":" + pathValue + "文本");
        try {
            String s = findEle(selector, pathValue).getText();
            log.sendStepLog(StepType.INFO, "", "文本获取结果: " + s);
            try {
                expect = TextHandler.replaceTrans(expect, globalParams);
                assertEquals(s, expect);
                log.sendStepLog(StepType.INFO, "验证文本", "真实值： " + s + " 期望值： " + expect);
            } catch (AssertionError e) {
                log.sendStepLog(StepType.ERROR, "验证" + des + "文本失败！", "");
                handleDes.setE(e);
            }
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void longPressPoint(HandleDes handleDes, String des, String xy, int time) {
        try {
            double x = Double.parseDouble(xy.substring(0, xy.indexOf(",")));
            double y = Double.parseDouble(xy.substring(xy.indexOf(",") + 1));
            int[] point = computedPoint(x, y);
            handleDes.setStepDes("长按" + des);
            handleDes.setDetail("长按坐标" + time + "毫秒 (" + point[0] + "," + point[1] + ")");
            iosDriver.longPress(point[0], point[1], time);
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void keyCode(HandleDes handleDes, String key) {
        handleDes.setStepDes("按系统按键" + key + "键");
        handleDes.setDetail("");
        try {
            iosDriver.pressButton(key);
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void tap(HandleDes handleDes, String des, String xy) {
        try {
            double x = Double.parseDouble(xy.substring(0, xy.indexOf(",")));
            double y = Double.parseDouble(xy.substring(xy.indexOf(",") + 1));
            int[] point = computedPoint(x, y);
            handleDes.setStepDes("点击" + des);
            handleDes.setDetail("点击坐标(" + point[0] + "," + point[1] + ")");
            iosDriver.tap(point[0], point[1]);
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void swipePoint(HandleDes handleDes, String des1, String xy1, String des2, String xy2) {
        try {
            double x1 = Double.parseDouble(xy1.substring(0, xy1.indexOf(",")));
            double y1 = Double.parseDouble(xy1.substring(xy1.indexOf(",") + 1));
            int[] point1 = computedPoint(x1, y1);
            double x2 = Double.parseDouble(xy2.substring(0, xy2.indexOf(",")));
            double y2 = Double.parseDouble(xy2.substring(xy2.indexOf(",") + 1));
            int[] point2 = computedPoint(x2, y2);
            handleDes.setStepDes("滑动拖拽" + des1 + "到" + des2);
            handleDes.setDetail("拖动坐标(" + point1[0] + "," + point1[1] + ")到(" + point2[0] + "," + point2[1] + ")");
            iosDriver.swipe(point1[0], point1[1], point2[0], point2[1]);
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void swipe(HandleDes handleDes, String des, String selector, String pathValue, String des2, String selector2, String pathValue2) {
        try {
            IOSElement webElement = findEle(selector, pathValue);
            IOSElement webElement2 = findEle(selector2, pathValue2);
            int x1 = webElement.getRect().getCenter().getX();
            int y1 = webElement.getRect().getCenter().getY();
            int x2 = webElement2.getRect().getCenter().getX();
            int y2 = webElement2.getRect().getCenter().getY();
            handleDes.setStepDes("滑动拖拽" + des + "到" + des2);
            handleDes.setDetail("拖动坐标(" + x1 + "," + y1 + ")到(" + x2 + "," + y2 + ")");
            iosDriver.swipe(x1, y1, x2, y2);
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void longPress(HandleDes handleDes, String des, String selector, String pathValue, int time) {
        handleDes.setStepDes("长按" + des);
        handleDes.setDetail("长按控件元素" + time + "毫秒 ");
        try {
            IOSElement webElement = findEle(selector, pathValue);
            int x = webElement.getRect().getCenter().getX();
            int y = webElement.getRect().getCenter().getY();
            iosDriver.longPress(x, y, time);
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void clear(HandleDes handleDes, String des, String selector, String pathValue) {
        handleDes.setStepDes("清空" + des);
        handleDes.setDetail("清空" + selector + ": " + pathValue);
        try {
            findEle(selector, pathValue).clear();
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void isExistEle(HandleDes handleDes, String des, String selector, String pathValue, boolean expect) {
        handleDes.setStepDes("判断控件 " + des + " 是否存在");
        handleDes.setDetail("期望值：" + (expect ? "存在" : "不存在"));
        boolean hasEle = false;
        try {
            IOSElement w = findEle(selector, pathValue);
            if (w != null) {
                hasEle = true;
            }
        } catch (Exception e) {
        }
        try {
            assertEquals(hasEle, expect);
        } catch (AssertionError e) {
            handleDes.setE(e);
        }
    }

//    public void getTitle(HandleDes handleDes, String expect) {
//        String title = iosDriver.getTitle();
//        handleDes.setStepDes("验证网页标题");
//        handleDes.setDetail("标题：" + title + "，期望值：" + expect);
//        try {
//            assertEquals(title, expect);
//        } catch (AssertionError e) {
//            handleDes.setE(e);
//        }
//    }

    public void clickByImg(HandleDes handleDes, String des, String pathValue) throws Exception {
        handleDes.setStepDes("点击图片" + des);
        handleDes.setDetail(pathValue);
        File file = null;
        if (pathValue.startsWith("http")) {
            try {
                file = DownloadTool.download(pathValue);
            } catch (Exception e) {
                handleDes.setE(e);
                return;
            }
        }
        FindResult findResult = null;
        try {
            SIFTFinder siftFinder = new SIFTFinder();
            findResult = siftFinder.getSIFTFindResult(file, getScreenToLocal(), true);
        } catch (Exception e) {
            log.sendStepLog(StepType.WARN, "SIFT图像算法出错，切换算法中...",
                    "");
        }
        if (findResult != null) {
            String url = UploadTools.upload(findResult.getFile(), "imageFiles");
            log.sendStepLog(StepType.INFO, "图片定位到坐标：(" + findResult.getX() + "," + findResult.getY() + ")  耗时：" + findResult.getTime() + " ms",
                    url);
        } else {
            log.sendStepLog(StepType.INFO, "SIFT算法无法定位图片，切换AKAZE算法中...",
                    "");
            try {
                AKAZEFinder akazeFinder = new AKAZEFinder();
                findResult = akazeFinder.getAKAZEFindResult(file, getScreenToLocal(), true);
            } catch (Exception e) {
                log.sendStepLog(StepType.WARN, "AKAZE图像算法出错，切换模版匹配算法中...",
                        "");
            }
            if (findResult != null) {
                String url = UploadTools.upload(findResult.getFile(), "imageFiles");
                log.sendStepLog(StepType.INFO, "图片定位到坐标：(" + findResult.getX() + "," + findResult.getY() + ")  耗时：" + findResult.getTime() + " ms",
                        url);
            } else {
                log.sendStepLog(StepType.INFO, "AKAZE算法无法定位图片，切换模版匹配算法中...",
                        "");
                try {
                    TemMatcher temMatcher = new TemMatcher();
                    findResult = temMatcher.getTemMatchResult(file, getScreenToLocal(), true);
                } catch (Exception e) {
                    log.sendStepLog(StepType.WARN, "模版匹配算法出错",
                            "");
                }
                if (findResult != null) {
                    String url = UploadTools.upload(findResult.getFile(), "imageFiles");
                    log.sendStepLog(StepType.INFO, "图片定位到坐标：(" + findResult.getX() + "," + findResult.getY() + ")  耗时：" + findResult.getTime() + " ms",
                            url);
                } else {
                    handleDes.setE(new Exception("图片定位失败！"));
                }
            }
        }
        if (findResult != null) {
            try {
                iosDriver.tap(findResult.getX(), findResult.getY());
            } catch (Exception e) {
                log.sendStepLog(StepType.ERROR, "点击" + des + "失败！", "");
                handleDes.setE(e);
            }
        }
    }


    public void readText(HandleDes handleDes, String language, String text) throws Exception {
//        TextReader textReader = new TextReader();
//        String result = textReader.getTessResult(getScreenToLocal(), language);
//        log.sendStepLog(StepType.INFO, "",
//                "图像文字识别结果：<br>" + result);
//        String filter = result.replaceAll(" ", "");
        handleDes.setStepDes("图像文字识别");
        handleDes.setDetail("（该功能暂时关闭）期望包含文本：" + text);
//        if (!filter.contains(text)) {
//            handleDes.setE(new Exception("图像文字识别不通过！"));
//        }
    }

    public File getScreenToLocal() {
        File folder = new File("test-output");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        File output = new File(folder + File.separator + log.udId + Calendar.getInstance().getTimeInMillis() + ".png");
        try {
            byte[] bt = iosDriver.screenshot();
            FileImageOutputStream imageOutput = new FileImageOutputStream(output);
            imageOutput.write(bt, 0, bt.length);
            imageOutput.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return output;
    }

    public void checkImage(HandleDes handleDes, String des, String pathValue, double matchThreshold) throws Exception {
        log.sendStepLog(StepType.INFO, "开始检测" + des + "兼容", "检测与当前设备截图相似度，期望相似度为" + matchThreshold + "%");
        File file = null;
        if (pathValue.startsWith("http")) {
            file = DownloadTool.download(pathValue);
        }
        SimilarityChecker similarityChecker = new SimilarityChecker();
        double score = similarityChecker.getSimilarMSSIMScore(file, getScreenToLocal(), true);
        handleDes.setStepDes("检测" + des + "图片相似度");
        handleDes.setDetail("相似度为" + score * 100 + "%");
        if (score == 0) {
            handleDes.setE(new Exception("图片相似度检测不通过！比对图片分辨率不一致！"));
        } else if (score < (matchThreshold / 100)) {
            handleDes.setE(new Exception("图片相似度检测不通过！expect " + matchThreshold + " but " + score * 100));
        }
    }

    public void siriCommand(HandleDes handleDes, String command) {
        handleDes.setStepDes("siri指令");
        handleDes.setDetail("对siri发送指令： " + command);
        try {
            iosDriver.sendSiriCommand(command);
        } catch (Exception e) {
            handleDes.setE(e);
        }
    }

    public void exceptionLog(Throwable e) {
        log.sendStepLog(StepType.WARN, "", "异常信息： " + e.fillInStackTrace().toString());
    }

    public void errorScreen() {
        try {
            File folder = new File("test-output");
            if (!folder.exists()) {
                folder.mkdirs();
            }
            byte[] bt = iosDriver.screenshot();
            File output = new File(folder + File.separator + UUID.randomUUID() + ".png");
            FileImageOutputStream imageOutput = new FileImageOutputStream(output);
            imageOutput.write(bt, 0, bt.length);
            imageOutput.close();
            log.sendStepLog(StepType.WARN, "获取异常截图", UploadTools
                    .upload(output, "imageFiles"));
        } catch (Exception e) {
            log.sendStepLog(StepType.ERROR, "捕获截图失败", "");
        }
    }

    public String stepScreen(HandleDes handleDes) {
        handleDes.setStepDes("获取截图");
        handleDes.setDetail("");
        String url = "";
        try {
            File folder = new File("test-output");
            if (!folder.exists()) {
                folder.mkdirs();
            }
            File output = new File(folder + File.separator + udId + Calendar.getInstance().getTimeInMillis() + ".png");
            byte[] bt = iosDriver.screenshot();
            FileImageOutputStream imageOutput = new FileImageOutputStream(output);
            imageOutput.write(bt, 0, bt.length);
            imageOutput.close();
            url = UploadTools.upload(output, "imageFiles");
            handleDes.setDetail(url);
        } catch (Exception e) {
            handleDes.setE(e);
        }
        return url;
    }

    public void pause(HandleDes handleDes, int time) {
        handleDes.setStepDes("强制等待");
        handleDes.setDetail("等待" + time + " ms");
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            handleDes.setE(e);
        }
    }

    public void publicStep(HandleDes handleDes, String name, JSONArray stepArray) {
        handleDes.setStepDes("执行公共步骤 " + name);
        handleDes.setDetail("");
        log.sendStepLog(StepType.WARN, "公共步骤「" + name + "」开始执行", "");
        for (Object publicStep : stepArray) {
            JSONObject stepDetail = (JSONObject) publicStep;
            try {
                SpringTool.getBean(StepHandlers.class)
                        .runStep(stepDetail, handleDes, (RunStepThread) Thread.currentThread());
            } catch (Throwable e) {
                handleDes.setE(e);
                break;
            }
        }
        log.sendStepLog(StepType.WARN, "公共步骤「" + name + "」执行完毕", "");
    }

    public IOSElement findEle(String selector, String pathValue) throws SonicRespException {
        IOSElement we = null;
        pathValue = TextHandler.replaceTrans(pathValue, globalParams);
        switch (selector) {
            case "id":
                we = iosDriver.findElement(IOSSelector.Id, pathValue);
                break;
            case "accessibilityId":
                we = iosDriver.findElement(IOSSelector.ACCESSIBILITY_ID, pathValue);
                break;
            case "nsPredicate":
                we = iosDriver.findElement(IOSSelector.PREDICATE, pathValue);
                break;
            case "name":
                we = iosDriver.findElement(IOSSelector.NAME, pathValue);
                break;
            case "xpath":
                we = iosDriver.findElement(IOSSelector.XPATH, pathValue);
                break;
            case "classChain":
                we = iosDriver.findElement(IOSSelector.CLASS_CHAIN, pathValue);
                break;
            case "className":
                we = iosDriver.findElement(IOSSelector.CLASS_NAME, pathValue);
                break;
            case "linkText":
                we = iosDriver.findElement(IOSSelector.LINK_TEXT, pathValue);
                break;
            case "partialLinkText":
                we = iosDriver.findElement(IOSSelector.PARTIAL_LINK_TEXT, pathValue);
                break;
            default:
                log.sendStepLog(StepType.ERROR, "查找控件元素失败", "这个控件元素类型: " + selector + " 不存在!!!");
                break;
        }
        return we;
    }

    public void setFindElementInterval(HandleDes handleDes, int retry, int interval) {
        handleDes.setStepDes("Set Global Find Element Interval");
        handleDes.setDetail(String.format("Retry count: %d, retry interval: %d ms", retry, interval));
        iosDriver.setDefaultFindElementInterval(retry, interval);
    }

    public void stepHold(HandleDes handleDes, int time) {
        handleDes.setStepDes("设置全局步骤间隔");
        handleDes.setDetail("间隔" + time + " ms");
        holdTime = time;
    }

    public void sendKeyForce(HandleDes handleDes, String text) {
        text = TextHandler.replaceTrans(text, globalParams);
        handleDes.setStepDes("键盘输入文本");
        handleDes.setDetail("键盘输入" + text);
        try {
            iosDriver.sendKeys(text);
        } catch (SonicRespException e) {
            handleDes.setE(e);
        }
    }

    public void setPasteboard(HandleDes handleDes, String text) {
        text = TextHandler.replaceTrans(text, globalParams);
        handleDes.setStepDes("Set text to clipboard");
        handleDes.setDetail("Set text: " + text);
        try {
            iosDriver.appActivate("com.apple.springboard");
            iosDriver.sendSiriCommand("open WebDriverAgentRunner-Runner");
            Thread.sleep(2000);
            iosDriver.setPasteboard(PasteboardType.PLAIN_TEXT, text);
            iosDriver.pressButton(SystemButton.HOME);
        } catch (SonicRespException | InterruptedException e) {
            handleDes.setE(e);
        }
    }

    public String getPasteboard(HandleDes handleDes) {
        String text = "";
        handleDes.setStepDes("Get clipboard text");
        handleDes.setDetail("");
        try {
            iosDriver.appActivate("com.apple.springboard");
            iosDriver.sendSiriCommand("open WebDriverAgentRunner-Runner");
            Thread.sleep(2000);
            text = new String(iosDriver.getPasteboard(PasteboardType.PLAIN_TEXT), StandardCharsets.UTF_8);
            iosDriver.pressButton(SystemButton.HOME);
        } catch (SonicRespException | InterruptedException e) {
            handleDes.setE(e);
        }
        return text;
    }

    private int holdTime = 0;

    private int[] computedPoint(double x, double y) throws SonicRespException {
        if (x <= 1 && y <= 1) {
            WindowSize windowSize = iosDriver.getWindowSize();
            x = windowSize.getWidth() * x;
            y = windowSize.getHeight() * y;
        }
        return new int[]{(int) x, (int) y};
    }

    public void runScript(HandleDes handleDes, String script, String type) {
        handleDes.setStepDes("Run Custom Scripts");
        handleDes.setDetail("Script: <br>" + script);
        try {
            switch (type) {
                case "Groovy":
                    GroovyScript groovyScript = new GroovyScriptImpl();
                    groovyScript.runIOS(iosDriver, udId, globalParams, log, script);
                    break;
                case "Python":
                    File temp = new File("test-output" + File.separator + UUID.randomUUID() + ".py");
                    if (!temp.exists()) {
                        temp.createNewFile();
                        FileWriter fileWriter = new FileWriter(temp);
                        fileWriter.write(script);
                        fileWriter.close();
                    }
                    CommandLine cmdLine = new CommandLine(String.format("python %s", temp.getAbsolutePath()));
                    cmdLine.addArgument(iosDriver.getSessionId(), false);
                    cmdLine.addArgument(udId, false);
                    cmdLine.addArgument(globalParams.toJSONString(), false);
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
                    try {
                        DefaultExecutor executor = new DefaultExecutor();
                        executor.setStreamHandler(streamHandler);
                        int exit = executor.execute(cmdLine);
                        log.sendStepLog(StepType.INFO, "", "Run result: <br>" + outputStream);
                        Assert.assertEquals(exit, 0);
                    } catch (Exception e) {
                        handleDes.setE(e);
                    } finally {
                        outputStream.close();
                        streamHandler.stop();
                        temp.delete();
                    }
                    break;
            }
        } catch (Throwable e) {
            handleDes.setE(e);
        }
    }

    public void runStep(JSONObject stepJSON, HandleDes handleDes) throws Throwable {
        JSONObject step = stepJSON.getJSONObject("step");
        JSONArray eleList = step.getJSONArray("elements");
        Thread.sleep(holdTime);
        switch (step.getString("stepType")) {
            case "stepHold":
                stepHold(handleDes, Integer.parseInt(step.getString("content")));
                break;
            case "siriCommand":
                siriCommand(handleDes, step.getString("content"));
                break;
            case "readText":
                readText(handleDes, step.getString("content"), step.getString("text"));
                break;
            case "clickByImg":
                clickByImg(handleDes, eleList.getJSONObject(0).getString("eleName")
                        , eleList.getJSONObject(0).getString("eleValue"));
                break;
            case "click":
                click(handleDes, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                        , eleList.getJSONObject(0).getString("eleValue"));
                break;
            case "sendKeys":
                sendKeys(handleDes, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                        , eleList.getJSONObject(0).getString("eleValue"), step.getString("content"));
                break;
            case "getText":
                getTextAndAssert(handleDes, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                        , eleList.getJSONObject(0).getString("eleValue"), step.getString("content"));
                break;
            case "isExistEle":
                isExistEle(handleDes, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                        , eleList.getJSONObject(0).getString("eleValue"), step.getBoolean("content"));
                break;
            case "clear":
                clear(handleDes, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                        , eleList.getJSONObject(0).getString("eleValue"));
                break;
            case "longPress":
                longPress(handleDes, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                        , eleList.getJSONObject(0).getString("eleValue"), Integer.parseInt(step.getString("content")));
                break;
            case "swipe":
                swipePoint(handleDes, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleValue")
                        , eleList.getJSONObject(1).getString("eleName"), eleList.getJSONObject(1).getString("eleValue"));
                break;
            case "swipe2":
                swipe(handleDes, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType"), eleList.getJSONObject(0).getString("eleValue")
                        , eleList.getJSONObject(1).getString("eleName"), eleList.getJSONObject(1).getString("eleType"), eleList.getJSONObject(1).getString("eleValue"));
                break;
            case "tap":
                tap(handleDes, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleValue"));
                break;
            case "longPressPoint":
                longPressPoint(handleDes, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleValue")
                        , Integer.parseInt(step.getString("content")));
                break;
            case "pause":
                pause(handleDes, Integer.parseInt(step.getString("content")));
                break;
            case "checkImage":
                checkImage(handleDes, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleValue")
                        , step.getDouble("content"));
                break;
            case "stepScreen":
                stepScreen(handleDes);
                break;
            case "openApp":
                openApp(handleDes, step.getString("text"));
                break;
            case "terminate":
                terminate(handleDes, step.getString("text"));
                break;
            case "install":
                install(handleDes, step.getString("text"));
                break;
            case "uninstall":
                uninstall(handleDes, step.getString("text"));
                break;
            case "runBack":
                runBackground(handleDes, Long.parseLong(step.getString("content")));
                break;
            case "lock":
                lock(handleDes);
                break;
            case "unLock":
                unLock(handleDes);
                break;
            case "keyCode":
                keyCode(handleDes, step.getString("content"));
                break;
            case "assertEquals":
            case "assertTrue":
            case "assertNotTrue":
                String actual = TextHandler.replaceTrans(step.getString("text"), globalParams);
                String expect = TextHandler.replaceTrans(step.getString("content"), globalParams);
                asserts(handleDes, actual, expect, step.getString("stepType"));
                break;
            case "getTextValue":
                globalParams.put(step.getString("content"), getText(handleDes, eleList.getJSONObject(0).getString("eleName")
                        , eleList.getJSONObject(0).getString("eleType"), eleList.getJSONObject(0).getString("eleValue")));
                break;
            case "sendKeyForce":
                sendKeyForce(handleDes, step.getString("content"));
                break;
//            case "hideKey":
//                hideKey(handleDes);
//                break;
//            case "monkey":
//                runMonkey(handleDes, step.getJSONObject("content"), step.getJSONArray("text").toJavaList(JSONObject.class));
//                break;
            case "publicStep":
                publicStep(handleDes, step.getString("content"), stepJSON.getJSONArray("pubSteps"));
                return;
            case "findElementInterval":
                setFindElementInterval(handleDes, step.getInteger("content"), step.getInteger("text"));
                break;
            case "setPasteboard":
                setPasteboard(handleDes, step.getString("content"));
                break;
            case "getPasteboard":
                globalParams.put(step.getString("content"), getPasteboard(handleDes));
                break;
            case "runScript":
                runScript(handleDes, step.getString("content"), step.getString("text"));
                break;
        }
        switchType(step, handleDes);
    }

    public void switchType(JSONObject stepJson, HandleDes handleDes) throws Throwable {
        Integer error = stepJson.getInteger("error");
        String stepDes = handleDes.getStepDes();
        String detail = handleDes.getDetail();
        Throwable e = handleDes.getE();
        if (e != null) {
            switch (error) {
                case ErrorType.IGNORE:
                    if (stepJson.getInteger("conditionType").equals(ConditionEnum.NONE.getValue())) {
                        log.sendStepLog(StepType.PASS, stepDes + "异常！已忽略...", detail);
                    } else {
                        ConditionEnum conditionType =
                                SonicEnum.valueToEnum(ConditionEnum.class, stepJson.getInteger("conditionType"));
                        String des = "「%s」步骤「%s」异常".formatted(conditionType.getName(), stepDes);
                        log.sendStepLog(StepType.ERROR, des, detail);
                        exceptionLog(e);
                    }
                    break;
                case ErrorType.WARNING:
                    log.sendStepLog(StepType.WARN, stepDes + "异常！", detail);
                    setResultDetailStatus(ResultDetailStatus.WARN);
                    errorScreen();
                    exceptionLog(e);
                    break;
                case ErrorType.SHUTDOWN:
                    log.sendStepLog(StepType.ERROR, stepDes + "异常！", detail);
                    setResultDetailStatus(ResultDetailStatus.FAIL);
                    errorScreen();
                    exceptionLog(e);
                    throw e;
            }
            // 非条件步骤清除异常对象
            if (stepJson.getInteger("conditionType").equals(ConditionEnum.NONE.getValue())) {
                handleDes.clear();
            }
        } else {
            log.sendStepLog(StepType.PASS, stepDes, detail);
        }
    }
}