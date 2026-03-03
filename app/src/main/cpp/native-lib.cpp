#include <jni.h>
#include <android/bitmap.h>
#include <vector>
#include <stdio.h>
#include <string.h>

std::vector<int> nativeLutR, nativeLutG, nativeLutB;
int nativeLutSize = 0;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_loadLutNative(JNIEnv* env, jobject, jstring path) {
    const char *file_path = env->GetStringUTFChars(path, NULL);
    FILE *file = fopen(file_path, "r");
    env->ReleaseStringUTFChars(path, file_path);
    if (!file) return JNI_FALSE;

    nativeLutR.clear(); nativeLutG.clear(); nativeLutB.clear();
    nativeLutSize = 0;
    
    char line[256];
    while(fgets(line, sizeof(line), file)) {
        if (strncmp(line, "LUT_3D_SIZE", 11) == 0) {
            sscanf(line, "LUT_3D_SIZE %d", &nativeLutSize);
            int expected = nativeLutSize * nativeLutSize * nativeLutSize;
            nativeLutR.reserve(expected);
            nativeLutG.reserve(expected);
            nativeLutB.reserve(expected);
        }
        float r, g, b;
        if (sscanf(line, "%f %f %f", &r, &g, &b) == 3) {
            nativeLutR.push_back((int)(r * 255.0f));
            nativeLutG.push_back((int)(g * 255.0f));
            nativeLutB.push_back((int)(b * 255.0f));
        }
    }
    fclose(file);
    return nativeLutSize > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_ma1co_pmcademo_app_LutEngine_applyLutNative(JNIEnv* env, jobject, jobject bitmap) {
    if (nativeLutSize == 0) return JNI_FALSE;

    AndroidBitmapInfo info;
    void* pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return JNI_FALSE;

    int width = info.width;
    int height = info.height;
    int lutSize2 = nativeLutSize * nativeLutSize;
    int lutMax = nativeLutSize - 1;
    uint32_t* line = (uint32_t*) pixels;

    int map[256];
    for (int i = 0; i < 256; i++) {
        map[i] = (i * lutMax * 128) / 255;
    }

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            uint32_t pixel = line[y * width + x];
            
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;

            int fX = map[r]; int fY = map[g]; int fZ = map[b];

            int x0 = fX >> 7; int y0 = fY >> 7; int z0 = fZ >> 7;
            int x1 = x0 + 1; if (x1 > lutMax) x1 = lutMax;
            int y1 = y0 + 1; if (y1 > lutMax) y1 = lutMax;
            int z1 = z0 + 1; if (z1 > lutMax) z1 = lutMax;

            int dx = fX & 0x7F; int dy = fY & 0x7F; int dz = fZ & 0x7F;
            int idx_x = 128 - dx; int idy = 128 - dy; int idz = 128 - dz;

            int w000 = idx_x * idy * idz; int w100 = dx * idy * idz;
            int w010 = idx_x * dy * idz;  int w110 = dx * dy * idz;
            int w001 = idx_x * idy * dz;  int w101 = dx * idy * dz;
            int w011 = idx_x * dy * dz;   int w111 = dx * dy * dz;

            int y0_idx = y0 * nativeLutSize; int y1_idx = y1 * nativeLutSize;
            int z0_idx = z0 * lutSize2;      int z1_idx = z1 * lutSize2;

            int i000 = x0 + y0_idx + z0_idx; int i100 = x1 + y0_idx + z0_idx;
            int i010 = x0 + y1_idx + z0_idx; int i110 = x1 + y1_idx + z0_idx;
            int i001 = x0 + y0_idx + z1_idx; int i101 = x1 + y0_idx + z1_idx;
            int i011 = x0 + y1_idx + z1_idx; int i111 = x1 + y1_idx + z1_idx;

            int outR = (nativeLutR[i000]*w000 + nativeLutR[i100]*w100 + nativeLutR[i010]*w010 + nativeLutR[i110]*w110 + nativeLutR[i001]*w001 + nativeLutR[i101]*w101 + nativeLutR[i011]*w011 + nativeLutR[i111]*w111) >> 21;
            int outG = (nativeLutG[i000]*w000 + nativeLutG[i100]*w100 + nativeLutG[i010]*w010 + nativeLutG[i110]*w110 + nativeLutG[i001]*w001 + nativeLutG[i101]*w101 + nativeLutG[i011]*w011 + nativeLutG[i111]*w111) >> 21;
            int outB = (nativeLutB[i000]*w000 + nativeLutB[i100]*w100 + nativeLutB[i010]*w010 + nativeLutB[i110]*w110 + nativeLutB[i001]*w001 + nativeLutB[i101]*w101 + nativeLutB[i011]*w011 + nativeLutB[i111]*w111) >> 21;

            line[y * width + x] = (0xFF << 24) | (outR << 16) | (outG << 8) | outB;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}