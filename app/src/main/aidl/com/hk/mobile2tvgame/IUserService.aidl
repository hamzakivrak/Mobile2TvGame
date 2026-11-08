package com.hk.mobile2tvgame;

interface IUserService {
    // Shizuku icin zorunlu
    void destroy() = 16777114;

    // jar: scrcpy-server dosyasinin baytlari. Donus: test raporu (metin).
    String runTest(in byte[] jar, String version, String startApp, String display) = 1;
}
