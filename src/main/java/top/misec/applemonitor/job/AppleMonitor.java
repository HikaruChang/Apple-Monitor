package top.misec.applemonitor.job;

import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import top.misec.applemonitor.config.*;
import top.misec.applemonitor.push.impl.FeiShuBotPush;
import top.misec.applemonitor.push.pojo.feishu.FeiShuPushDTO;
import top.misec.bark.BarkPush;
import top.misec.bark.enums.SoundEnum;
import top.misec.bark.pojo.PushDetails;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author MoshiCoCo
 */
@Slf4j
public class AppleMonitor {
    private final AppCfg CONFIG = CfgSingleton.getInstance().config;

    /**
     * 最大重試次數
     */
    private static final int MAX_RETRY = 3;

    public void monitor() {

        List<DeviceItem> deviceItemList = CONFIG.getAppleTaskConfig().getDeviceCodeList();
        // 监视机型型号

        try {
            for (DeviceItem deviceItem : deviceItemList) {
                doMonitor(deviceItem);
                // 隨機延遲 5-12 秒，模擬真實用戶行為
                Thread.sleep(RandomUtil.randomInt(5000, 12000));
            }
        } catch (Exception e) {
            log.error("AppleMonitor Error", e);
        }
    }

    public void pushAll(String content, List<PushConfig> pushConfigs) {

        pushConfigs.forEach(push -> {

            if (StrUtil.isAllNotEmpty(push.getBarkPushUrl(), push.getBarkPushToken())) {
                BarkPush barkPush = new BarkPush(push.getBarkPushUrl(), push.getBarkPushToken());
                PushDetails pushDetails = PushDetails.builder()
                        .title("苹果商店监控")
                        .body(content)
                        .category("苹果商店监控")
                        .group("Apple Monitor")
                        .sound(StrUtil.isEmpty(push.getBarkPushSound()) ? SoundEnum.GLASS.getSoundName()
                                : push.getBarkPushSound())
                        .build();
                barkPush.simpleWithResp(pushDetails);
            }
            if (StrUtil.isAllNotEmpty(push.getFeishuBotSecret(), push.getFeishuBotWebhooks())) {

                FeiShuBotPush.pushTextMessage(FeiShuPushDTO.builder()
                        .text(content).secret(push.getFeishuBotSecret())
                        .botWebHooks(push.getFeishuBotWebhooks())
                        .build());
            }
        });

    }

    public void doMonitor(DeviceItem deviceItem) {

        Map<String, Object> queryMap = new HashMap<>(5);
        queryMap.put("pl", "true");
        queryMap.put("mts.0", "regular");
        queryMap.put("parts.0", deviceItem.getDeviceCode());
        queryMap.put("location", CONFIG.getAppleTaskConfig().getLocation());

        String baseCountryUrl = CountryEnum.getUrlByCountry(CONFIG.getAppleTaskConfig().getCountry());

        String url = baseCountryUrl + "/shop/fulfillment-messages?"
                + URLUtil.buildQuery(queryMap, CharsetUtil.CHARSET_UTF_8);

        try {
            JSONObject responseJsonObject = executeRequestWithRetry(url, baseCountryUrl, deviceItem.getDeviceCode());

            if (responseJsonObject == null) {
                return;
            }

            JSONObject pickupMessage = responseJsonObject.getJSONObject("body").getJSONObject("content")
                    .getJSONObject("pickupMessage");

            JSONArray stores = pickupMessage.getJSONArray("stores");

            if (stores == null) {
                log.info("您可能填错产品代码了，目前仅支持监控中国和日本地区的产品，注意不同国家的机型型号不同，下面是是错误信息");
                log.debug(pickupMessage.toString());
                return;
            }

            if (stores.isEmpty()) {
                log.info("您所在的 {} 附近没有Apple直营店，请检查您的地址是否正确", CONFIG.getAppleTaskConfig().getLocation());
            }

            stores.stream().filter(store -> {
                if (deviceItem.getStoreWhiteList().isEmpty()) {
                    return true;
                } else {
                    return filterStore((JSONObject) store, deviceItem);
                }
            }).forEach(k -> {

                JSONObject storeJson = (JSONObject) k;

                JSONObject partsAvailability = storeJson.getJSONObject("partsAvailability");

                String storeNames = storeJson.getString("storeName").trim();
                String deviceName = partsAvailability.getJSONObject(deviceItem.getDeviceCode())
                        .getJSONObject("messageTypes").getJSONObject("regular").getString("storePickupProductTitle");
                String productStatus = partsAvailability.getJSONObject(deviceItem.getDeviceCode())
                        .getString("pickupSearchQuote");

                String strTemp = "门店:{},型号:{},状态:{}";

                String content = StrUtil.format(strTemp, storeNames, deviceName, productStatus);

                if (judgingStoreInventory(storeJson, deviceItem.getDeviceCode())) {
                    JSONObject retailStore = storeJson.getJSONObject("retailStore");
                    content += buildPickupInformation(retailStore);
                    log.info(content);

                    pushAll(content, deviceItem.getPushConfigs());

                }
                log.info(content);
            });

        } catch (Exception e) {
            log.error("AppleMonitor error", e);
        }

    }

    /**
     * check store inventory
     *
     * @param storeJson   store json
     * @param productCode product code
     * @return boolean
     */
    private boolean judgingStoreInventory(JSONObject storeJson, String productCode) {

        JSONObject partsAvailability = storeJson.getJSONObject("partsAvailability");
        String status = partsAvailability.getJSONObject(productCode).getString("pickupDisplay");
        return "available".equals(status);

    }

    /**
     * build pickup information
     *
     * @param retailStore retailStore
     * @return pickup message
     */
    private String buildPickupInformation(JSONObject retailStore) {
        String distanceWithUnit = retailStore.getString("distanceWithUnit");
        String twoLineAddress = retailStore.getJSONObject("address").getString("twoLineAddress");
        if (StrUtil.isEmpty(twoLineAddress)) {
            twoLineAddress = "暂无取货地址";
        }

        String daytimePhone = retailStore.getJSONObject("address").getString("daytimePhone");
        if (StrUtil.isEmpty(daytimePhone)) {
            daytimePhone = "暂无联系电话";
        }

        String lo = CONFIG.getAppleTaskConfig().getLocation();
        String messageTemplate = "\n取货地址:{},电话:{},距离{}:{}";
        return StrUtil.format(messageTemplate, twoLineAddress.replace("\n", " "), daytimePhone, lo, distanceWithUnit);
    }

    private boolean filterStore(JSONObject storeInfo, DeviceItem deviceItem) {
        String storeName = storeInfo.getString("storeName");
        return deviceItem.getStoreWhiteList().stream().anyMatch(k -> storeName.contains(k) || k.contains(storeName));
    }

    /**
     * 執行帶有重試機制的 HTTP 請求
     *
     * @param url            請求 URL
     * @param baseCountryUrl 基礎國家 URL
     * @param productCode    產品代碼
     * @return 響應 JSON 對象，失敗返回 null
     */
    private JSONObject executeRequestWithRetry(String url, String baseCountryUrl, String productCode) {
        for (int retry = 0; retry < MAX_RETRY; retry++) {
            try {
                // 每次重試前隨機延遲
                if (retry > 0) {
                    int delay = RandomUtil.randomInt(5000, 15000);
                    log.debug("第 {} 次重試，等待 {} 毫秒", retry + 1, delay);
                    Thread.sleep(delay);
                }

                Map<String, List<String>> headers = buildHeaders(baseCountryUrl, productCode);

                // 清除 Hutool 默認的 headers，使用 clearHeaders() 確保不會添加額外 headers
                HttpRequest request = HttpRequest.get(url)
                        .clearHeaders() // 清除所有默認 headers
                        .timeout(30000)
                        .setFollowRedirects(true);

                // 逐一設置 headers（不使用 header(Map) 以避免重複）
                headers.forEach((key, values) -> {
                    if (values != null && !values.isEmpty()) {
                        request.header(key, values.get(0), false); // false = 不追加，覆蓋
                    }
                });

                // 調試：輸出請求 headers
                if (retry == 0) {
                    log.debug("Request URL: {}", url);
                    log.debug("Request Headers: {}", request.headers());
                }

                try (HttpResponse httpResponse = request.execute()) {

                    int statusCode = httpResponse.getStatus();

                    if (httpResponse.isOk()) {
                        return JSONObject.parseObject(httpResponse.body());
                    }

                    if (statusCode == 403 || statusCode == 429 || statusCode == 541) {
                        log.warn("請求被限制 (HTTP {}), 第 {} 次重試...", statusCode, retry + 1);
                        continue;
                    }

                    log.info("請求失敗 (HTTP {})，請稍後再試", statusCode);
                    return null;
                }
            } catch (Exception e) {
                log.warn("請求異常，第 {} 次重試: {}", retry + 1, e.getMessage());
            }
        }

        log.info("請求過於頻繁，已達最大重試次數，請調整 cronExpressions，建議您參考推薦的 cron 表達式");
        return null;
    }

    /**
     * build request headers - 完全匹配瀏覽器請求
     *
     * @param baseCountryUrl base country url
     * @param productCode    product code
     * @return headers
     */
    private Map<String, List<String>> buildHeaders(String baseCountryUrl, String productCode) {

        Map<String, List<String>> headers = new HashMap<>(16);

        // 固定使用 Chrome 143 的 User-Agent（與成功的 curl 一致）
        headers.put(Header.USER_AGENT.getValue(), List.of(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"));

        // Referer
        headers.put(Header.REFERER.getValue(), List.of(baseCountryUrl + "/shop/buy-iphone/iphone-17-pro"));

        // Accept headers
        headers.put(Header.ACCEPT.getValue(), List.of("*/*"));
        headers.put(Header.ACCEPT_LANGUAGE.getValue(), List.of("zh-CN,zh-TW;q=0.9"));

        // Sec-CH-UA 系列 headers（與 Chrome 143 匹配）
        headers.put("sec-ch-ua", List.of("\"Google Chrome\";v=\"143\""));
        headers.put("sec-ch-ua-mobile", List.of("?0"));
        headers.put("sec-ch-ua-platform", List.of("\"macOS\""));

        // Sec-Fetch headers
        headers.put("Sec-Fetch-Dest", List.of("empty"));
        headers.put("Sec-Fetch-Mode", List.of("cors"));
        headers.put("Sec-Fetch-Site", List.of("same-origin"));

        // 添加 Cookie
        String cookie = CONFIG.getAppleTaskConfig().getCookie();
        if (StrUtil.isNotBlank(cookie)) {
            headers.put(Header.COOKIE.getValue(), List.of(cookie));
        }

        return headers;
    }
}
