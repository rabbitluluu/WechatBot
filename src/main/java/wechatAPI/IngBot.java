package wechatAPI;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wechatAPI.bots.WeatherBot;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Created on 17-2-6.
 *
 * @author ing
 * @version 1
 */
public class IngBot {
    // todo 终端日志颜色区分
    private static Logger log = LoggerFactory.getLogger(IngBot.class);
    private static ObjectMapper mapper = new ObjectMapper();
    private static final boolean DEBUG = false;

    // 状态码
    private static final String SUCCESS = "200";
    private static final String SCANED = "201";
    private static final String TIMEOUT = "408";

    private boolean isBigContact = false;

    // 参数
    private String loginUrl = "";
    private String uuid = "";
    private String qrcodeUrl = "";
    private String redirectUrl = "";
    private String baseUrl = "";
    private String baseHost = "";
    private String baseRequestContent = "";
    private String syncKeyStr = "";
    private String syncHost = "";

    private Map<String, String> initData = new HashMap<>();
    private Map<String, String> baseRequest = new HashMap<>();
    private Map<String, Object> syncKey = new HashMap<>();
    private Map<String, Object> myAccount = new HashMap<>(); // 当前账户
    private List<HashMap<String, Object>> memberList = new ArrayList<>(); // 联系人, 公众号, 群组, 特殊账号
    private List<HashMap<String, Object>> contactList = new ArrayList<>(); // 联系人列表
    private List<HashMap<String, Object>> publicList = new ArrayList<>(); // 公众账号列表
    private List<HashMap<String, Object>> specialList = new ArrayList<>(); // 特殊账号列表
    private List<HashMap<String, Object>> groupList = new ArrayList<>(); // 群聊列表

    // 消息相关
    private String[] fullUserNameList = null;

//    private HashMap groupMembers; // 所有群组的成员 {'group_id1': [member1, member2, ...], ...}
//    private HashMap accountInfo; // 所有账户 {'group_member':{'id':{'type':'group_member', 'info':{}}, ...}, 'normal_member':{'id':{}, ...}}

    public IngBot() {
        //　避免SSL报错
        System.setProperty("jsse.enableSNIExtension", "false");

        this.loginUrl = "https://login.weixin.qq.com/jslogin?appid=wx782c26e4c19acffb&redirect_uri=https%3A%2F%2Fwx.qq.com%2Fcgi-bin%2Fmmwebwx-bin%2Fwebwxnewloginpage&fun=new&lang=zh_CN&_=";
        this.qrcodeUrl = "https://login.weixin.qq.com/qrcode/";
        this.baseUrl = "https://wx.qq.com/cgi-bin/mmwebwx-bin";
        this.baseHost = "wx.qq.com";
    }

    public void getUuid() throws IOException {
        loginUrl = loginUrl + new Date().getTime();
        log.debug("loginUrl: " + loginUrl);
        String response = NetUtils.request(loginUrl);
        // e.g: window.QRLogin.code = 200; window.QRLogin.uuid = "wejZcbBd2w==";
        String code = StringUtils.substringBetween(response, "window.QRLogin.code = ", ";");
        uuid = StringUtils.substringBetween(response, "window.QRLogin.uuid = \"", "\";");

        log.debug("window.QRLogin.code = " + code);
        log.debug("window.QRLogin.uuid = " + uuid);
    }

    public void generateQrcode() throws IOException {
        qrcodeUrl += uuid;
        log.debug("qrcode url: " + qrcodeUrl);
        byte[] qrcodeDate = NetUtils.requestForBytes(qrcodeUrl);
        String imageUrl = System.getProperty("user.dir") + "/res/temp.png";
        NetUtils.writeToFile(imageUrl, qrcodeDate);
        Desktop desktop = Desktop.getDesktop();
        desktop.open(new File(imageUrl));
    }

    public void waitForLogin() throws IOException, InterruptedException {
        log.info("请扫描二维码登陆微信");
        String url = StringUtils.join("https://login.weixin.qq.com/cgi-bin/mmwebwx-bin/login?uuid=", uuid, "&tip=1&_=", new Date().getTime());
        log.debug("login url: " + url);
        while (true) {
            String response = NetUtils.request(url);
            log.debug("response: " + response);

            // 登陆过程中
            if (StringUtils.startsWith(response, "window.code=")) {
                String code = StringUtils.substringBetween(response, "window.code=", ";");
                log.debug("code: " + code);
                if (StringUtils.equals(code, SUCCESS)) {
                    log.info("登陆成功");
                    response = NetUtils.request(url);
                    redirectUrl = StringUtils.substringBetween(response, "window.redirect_uri=\"", "\";");
                    if (StringUtils.isNotEmpty(redirectUrl)) {
                        log.debug("redirectUrl: " + redirectUrl);
                        break;
                    }
                } else if (StringUtils.equals(code, SCANED))
                    log.info("请点击确认按钮");
                else if (StringUtils.equals(code, TIMEOUT)) {
                    log.info("登陆超时");
                    System.exit(-1);
                } else {
                    log.info("未知错误");
                    log.error(response);
                    System.exit(-1);
                }
            }

            //　轮询时间　1s
            Thread.sleep(1000);
        }
    }

    public void login() throws IOException {
        if (redirectUrl.length() < 4) {
            log.info("登陆失败");
            System.exit(-1);
        }
        //　获取uin和sid
        redirectUrl += "&fun=new";

        String response = NetUtils.request(redirectUrl);
        log.debug("response: " + response);
        initData = BotUtils.parseInitData(response);

        // 组装请求body
        baseRequest.put("Uin", initData.get("wxuin"));
        baseRequest.put("Sid", initData.get("wxsid"));
        baseRequest.put("Skey", initData.get("skey"));
        baseRequest.put("DeviceID", initData.get("deviceID"));

        Map<String, Map> tempData = new HashMap<>();
        tempData.put("BaseRequest", baseRequest);
        log.debug(tempData.toString());

        // 转换为json格式
        baseRequestContent = mapper.writeValueAsString(tempData);
    }

    public void init() throws IOException {
        String url = baseUrl + "/webwxinit?r=" + (new Date().getTime()) + "&lang=en_US&pass_ticket=" + initData.get("pass_ticket");
        log.debug("url: " + url);
        log.debug("baseRequestContent " + baseRequestContent);

        byte[] response = NetUtils.requestWithJsonForBytes(url, baseRequestContent);

        // 保存json数据到文件temp.json
        if (DEBUG) {
            String path = System.getProperty("user.dir") + "/res/init.json";
            NetUtils.writeToFile(path, response);
        }

        Map map = mapper.readValue(response, Map.class);
        syncKey = (Map<String, Object>) map.get("SyncKey");
        myAccount = (Map<String, Object>) map.get("User");

        log.debug("syncKey: " + syncKey);

        List<HashMap<String, Integer>> syncKeyList = (List<HashMap<String, Integer>>) syncKey.get("List");
        syncKeyStr = BotUtils.genSyncStr(syncKeyList);

        log.debug("syncKeyStr: " + syncKeyStr);
        log.debug("myAccount: " + myAccount);
    }

    public void statusNotify() throws IOException {
        String url = baseUrl + "/webwxstatusnotify?lang=zh_CN&pass_ticket=" + initData.get("pass_ticket");
        Map<String, Object> tempData = new HashMap<>();
        tempData.put("BaseRequest", baseRequest);
        tempData.put("Code", 3);
        tempData.put("FromUserName", myAccount.get("UserName"));
        tempData.put("ToUserName", myAccount.get("UserName"));
        tempData.put("ClientMsgId", new Date().getTime());
        NetUtils.requestWithJson(url, mapper.writeValueAsString(tempData));
    }

    public void getContact() throws IOException {
        // todo 如果通讯录联系人过多，这里会直接获取失败
        if (isBigContact)
            return;

        String url = baseUrl + "/webwxgetcontact?pass_ticket=" + initData.get("pass_ticket")
                + "&skey=" + initData.get("skey") + "&r=" + new Date().getTime();
        log.debug("url: " + url);


        try {
            byte[] response = NetUtils.requestWithJsonForBytes(url, "{}");
            if (DEBUG) {
                String path = System.getProperty("user.dir") + "/res/contacts.json";
                NetUtils.writeToFile(path, response);
            }

            Map map = mapper.readValue(response, Map.class);
            memberList = (ArrayList<HashMap<String, Object>>) map.get("MemberList");

            // 特殊用户名单
            List<String> specialUsers = Arrays.asList("newsapp", "fmessage", "filehelper", "weibo", "qqmail",
                    "fmessage", "tmessage", "qmessage", "qqsync", "floatbottle",
                    "lbsapp", "shakeapp", "medianote", "qqfriend", "readerapp",
                    "blogapp", "facebookapp", "masssendapp", "meishiapp",
                    "feedsapp", "voip", "blogappweixin", "weixin", "brandsessionholder",
                    "weixinreminder", "wxid_novlwrv3lqwv11", "gh_22b87fa7cb3c",
                    "officialaccounts", "notification_messages", "wxid_novlwrv3lqwv11",
                    "gh_22b87fa7cb3c", "wxitil", "userexperience_alarm", "notification_messages");

            for (HashMap<String, Object> contact : memberList) {
                if ((Integer.parseInt(contact.get("VerifyFlag").toString()) & 8) != 0) {
                    // 公众号
                    publicList.add(contact);
                    log.debug("公众号: " + contact.get("NickName"));
                } else if (specialUsers.contains(contact.get("UserName").toString())) {
                    // 特殊账户
                    specialList.add(contact);
                } else if (contact.get("UserName").toString().startsWith("@@")) {
                    // 群聊
                    groupList.add(contact);
                    log.debug("群聊: " + contact.get("NickName"));
                } else if (StringUtils.equals(contact.get("UserName").toString(), myAccount.get("UserName").toString())) {
                    // 自己
                    log.debug("欢迎:　" + contact.get("NickName"));
                } else {
                    contactList.add(contact);
                    log.debug("好友: " + contact.get("NickName"));
                }
            }
        } catch (Exception e) {
            isBigContact = true;
            log.info("联系人数量过多，尝试重新获取中");
            log.error(e.toString());
        }
    }

    public void testSyncCheck() throws IOException {
// webpush2 Failed to connect to webpush2.wx.qq.com/222.221.5.252:443
//        String[] hosts = {"webpush.", "webpush2."};
//        for (String host1 : hosts) {
//            syncHost = host1 + baseHost;
//            syncCheck();
//        }

        syncHost = "webpush." + baseHost;
        syncCheck();
    }

    public String[] syncCheck() throws IOException {
        Map<String, Object> request = new HashMap<>();
        request.put("r", new Date().getTime());
        request.put("sid", initData.get("wxsid"));
        request.put("uin", initData.get("wxuin"));
        request.put("skey", initData.get("skey"));
        request.put("deviceid", initData.get("deviceID"));
        request.put("synckey", syncKeyStr);
        request.put("_", new Date().getTime());

        String url = "https://" + syncHost + "/cgi-bin/mmwebwx-bin/synccheck?" + NetUtils.getUrlParamsByMap(request, false);
        log.debug("url: " + url);
        String response = NetUtils.request(url);
        log.debug("syncCheck response: " + response);

        String retcode = StringUtils.substringBetween(response, "retcode:\"", "\",");
        String selector = StringUtils.substringBetween(response, "selector:\"", "\"}");
        return new String[]{retcode, selector};
    }

    public Map sync() {
        String url = baseUrl + "/webwxsync?sid=" + initData.get("wxsid") + "&skey="
                + initData.get("skey") + "&lang=en_US&pass_ticket=" + initData.get("pass_ticket");
        log.debug("url: " + url);
        Map<String, Object> request = new HashMap<>();
        request.put("BaseRequest", baseRequest);
        request.put("SyncKey", syncKey);
        request.put("rr", ~(new Date().getTime()));
        String requestContent;
        try {
            requestContent = mapper.writeValueAsString(request);
            String response = NetUtils.requestWithJson(url, requestContent);
            Map dataMap = mapper.readValue(response, Map.class);
            int ret = (int) ((Map<String, Object>) dataMap.get("BaseResponse")).get("Ret");
            if (ret == 0) {
                syncKey = (Map<String, Object>) dataMap.get("SyncKey");
                List<HashMap<String, Integer>> syncKeyList = (List<HashMap<String, Integer>>) ((Map<String, Object>) dataMap.get("SyncKey")).get("List");
                syncKeyStr = BotUtils.genSyncStr(syncKeyList);
                log.debug("regenerate syncKeyStr: " + syncKeyStr);
            }
            log.debug("sync response: \n" + response);
            return dataMap;
        } catch (IOException e) {
            log.error(e.toString());
            return null;
        }
    }

    public void procMsg() throws IOException, InterruptedException {
        testSyncCheck();
        String[] status;
        long checkTime;
        while (true) {
            checkTime = new Date().getTime();
            try {
                status = syncCheck();
                if (StringUtils.equals(status[0], "1100")) {
                    log.info("从微信客户端上登出");
                    break;
                } else if (StringUtils.equals(status[0], "1101")) {
                    log.info("从其它设备上登了网页微信");
                    break;
                } else if (StringUtils.equals(status[0], "0")) {
                    Thread.sleep(1000);
                    if (StringUtils.equals(status[1], "2")) {
                        log.debug("有新消息");
                        handleMsg(sync());
                    } else if (StringUtils.equals(status[1], "3")) {
                        log.debug("未知事件");
                        handleMsg(sync());
                    } else if (StringUtils.equals(status[1], "4")) {
                        log.debug("通讯录更新");
                        sync();
                    } else if (StringUtils.equals(status[1], "6")) {
                        log.debug("可能是红包我也不知道哈哈哈");
                        handleMsg(sync());
                    } else if (StringUtils.equals(status[1], "7")) {
                        log.debug("手机上操作了微信");
                        handleMsg(sync());
                    } else if (StringUtils.equals(status[1], "0")) {
                        log.debug("无事件");
                    } else {
                        handleMsg(sync());
                    }
                } else {
                    log.debug("未知的status");
                    Thread.sleep(5000);
                }
            } catch (Exception e) {
                log.error(e.toString());
            }
            checkTime = new Date().getTime() - checkTime;
            if (checkTime < 0.8) {
                Thread.sleep(1 - checkTime);
            }
        }
    }

    /**
     * msg_type_id:
     * 0 -> Init
     * 1 -> Self
     * 2 -> FileHelper
     * 3 -> Group
     * 4 -> Contact
     * 5 -> Public
     * 6 -> Special
     * 99 -> Unknown
     *
     * @param map
     */
    public void handleMsg(Map map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        List<Map<String, Object>> msgList = (List<Map<String, Object>>) map.get("AddMsgList");

        int msgTypeId = -1;

        for (Map<String, Object> msg : msgList) {
            if ((int) msg.get("MsgType") == 51 && (int) msg.get("StatusNotifyCode") == 4) {
                // 消息初始化
                msgTypeId = 0;
                fullUserNameList = StringUtils.split(msg.get("StatusNotifyUserName").toString(), ",");
            } else if (StringUtils.equals(msg.get("FromUserName").toString(), myAccount.get("UserName").toString())) {
                // 自己给自己发送的消息
                msgTypeId = 1;
                String msgContent = msg.get("Content").toString();
                log.debug("自己发的消息: " + msgContent);
                try {
                    sendMsgByUid("test" + new Date().getTime(), msg.get("FromUserName").toString());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (isContact(msg.get("FromUserName").toString())) {
                // 好友发送的消息
                String name = getNameByUidFromContact(msg.get("FromUserName").toString(), true);
                if (StringUtils.isNotEmpty(name)) {
                    String msgContent = msg.get("Content").toString();
                    //天气bot测试
                    log.info("好友 " + name + ": " + msgContent);
                    if (StringUtils.startsWith(msgContent, "/w")) {
                        WeatherBot weatherBot = new WeatherBot();
                        String data = weatherBot.getWeather();
                        log.debug("data: " + data);
                        try {
                            sendMsgByUid(data, msg.get("FromUserName").toString());
                        } catch (IOException e) {
                            log.error("信息发送失败");
                            log.error(e.toString());
                        }
                    }
                }
            }
        }
    }

    public boolean isContact(String uid) {
        for (HashMap<String, Object> contact : contactList) {
            if (StringUtils.equals(uid, contact.get("UserName").toString())) {
                return true;
            }
        }
        return false;
    }

    public String getNameByUidFromContact(String uid, boolean isRemarkName) {
        for (HashMap<String, Object> contact : contactList) {
            if (StringUtils.equals(uid, contact.get("UserName").toString())) {
                if (isRemarkName) {
                    return contact.get("RemarkName").toString();
                } else {
                    return contact.get("NickName").toString();
                }
            }
        }
        return null;
    }

    public void sendMsgByUid(String data, String dst) throws IOException {
        String url = baseUrl + "/webwxsendmsg?pass_ticket=" + initData.get("pass_ticket");
        log.debug("url: " + url);
        Map params = new HashMap<String, Object>();
        Map msg = new HashMap<String, Object>();
        String temp = String.valueOf((new Date().getTime() * 10000) + ThreadLocalRandom.current().nextInt(0, 9999));
        String msgId = StringUtils.substring(temp, 0, temp.length() - 4);

        msg.put("Type", 1);
        msg.put("Content", data);
        msg.put("FromUserName", myAccount.get("UserName").toString());
        msg.put("ToUserName", dst);
        msg.put("LocalID", msgId);
        msg.put("ClientMsgId", msgId);

        params.put("BaseRequest", baseRequest);
        params.put("Msg", msg);

        String paramsContent = mapper.writeValueAsString(params);
        String response = NetUtils.requestWithJson(url, paramsContent);
        log.debug("response: " + response);
    }

    /**
     * 流程测试
     *
     * @param args
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        IngBot bot = new IngBot();
        bot.getUuid();
        bot.generateQrcode();
        bot.waitForLogin();
        bot.login();
        bot.init();
        bot.statusNotify();
        bot.getContact();
        bot.procMsg();
    }
}
