#include <jni.h>
#include <vector>
#include <string>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include <math.h>
#include <sys/time.h>
#include <pthread.h>
#include "jpeglib.h"
#include <android/log.h>
#include "process_kernel.h"
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

#define LOG_TAG "COOKBOOK_NATIVE"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

std::vector<uint8_t> nativeLut;
int nativeLutSize = 0;
std::vector<uint8_t> nativeGrainTexture;

struct my_error_mgr { struct jpeg_error_mgr pub; jmp_buf setjmp_buffer; };
METHODDEF(void) my_error_exit (j_common_ptr cinfo) {
    my_error_mgr * myerr = (my_error_mgr *) cinfo->err;
    longjmp(myerr->setjmp_buffer, 1);
}

struct ThreadWorkspace {
    int* work_0; int* work_1; int* work_2; int* work_h; int* h_line;
};

struct WorkerData {
    pthread_t thread;
    pthread_mutex_t mutex;
    pthread_cond_t cond_start;
    pthread_cond_t cond_done;
    bool start;
    bool done;
    bool terminate;

    int start_i, end_i, proc_rows_base, width, scaleDenom;
    unsigned char** rows; unsigned char** out_rows;
    bool use_rgb; int bloom, halation; long long start_time, cx, cy_center, vig_coef;
    int shadowToe, rollOff, colorChrome, chromeBlue, subSat, grain, grainSize, opac_m;
    int* map; uint8_t* roll_lut; const uint8_t* extTex; bool is_1024;
    bool applyCrop; int skip_top, final_h;
    ThreadWorkspace ws;
};

void* persistent_worker_func(void* arg) {
    WorkerData* d = (WorkerData*)arg;
    while (true) {
        pthread_mutex_lock(&d->mutex);
        while (!d->start && !d->terminate) pthread_cond_wait(&d->cond_start, &d->mutex);
        if (d->terminate) { pthread_mutex_unlock(&d->mutex); break; }
        d->start = false;
        pthread_mutex_unlock(&d->mutex);

        for (int i = d->start_i; i < d->end_i; i++) {
            int ay = d->proc_rows_base + i;
            if (!d->applyCrop || (ay >= d->skip_top && ay < d->skip_top + d->final_h)) {
                unsigned char* win[21]; for (int w=0; w<21; w++) win[w] = d->rows[i+w];
                memcpy(d->out_rows[i], win[10], d->width * 3);
                if (d->bloom > 0 || d->halation > 0) apply_bloom_halation(win, d->out_rows[i], d->width, ay, !d->use_rgb, d->bloom, d->halation, d->ws.work_0, d->ws.work_1, d->ws.work_2, d->ws.work_h, d->ws.h_line, d->scaleDenom);
                int tx = d->start_time % 1021; int ty = (d->start_time/13) % 1021;
                if (d->use_rgb) process_row_rgb(d->out_rows[i], d->width, ay, d->cx, d->cy_center, d->vig_coef, d->shadowToe, d->rollOff, d->colorChrome, d->chromeBlue, d->subSat, 0, 0, d->grain, d->grainSize, d->scaleDenom, d->opac_m, d->map, nativeLut.data(), nativeLutSize, nativeLutSize-1, nativeLutSize*nativeLutSize, d->extTex, d->is_1024, tx, ty);
                else process_row_yuv(d->out_rows[i], d->width, ay, d->cx, d->cy_center, d->vig_coef, d->shadowToe, d->rollOff, d->colorChrome, d->chromeBlue, d->subSat, 0, 0, d->grain, d->grainSize, d->scaleDenom, d->roll_lut, d->extTex, d->is_1024, tx, ty);
            }
        }
        pthread_mutex_lock(&d->mutex); d->done = true; pthread_cond_signal(&d->cond_done); pthread_mutex_unlock(&d->mutex);
    }
    return NULL;
}

long long get_time_ms() { struct timeval tv; gettimeofday(&tv, NULL); return (long long)tv.tv_sec*1000 + tv.tv_usec/1000; }

extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_LutEngine_loadLutNative(JNIEnv* env, jobject obj, jstring path) {
    nativeLut.clear(); nativeLutSize = 0; const char *fp = env->GetStringUTFChars(path, NULL); std::string ps(fp); std::string ex = ""; size_t dp = ps.find_last_of('.');
    if (dp != std::string::npos) { ex = ps.substr(dp); for(size_t i=0; i<ex.length(); i++) ex[i]=tolower(ex[i]); }
    if (ex == ".png") {
        int w, h, c; unsigned char *id = stbi_load(fp, &w, &h, &c, 3);
        if (id) {
            if (w*h <= 4000000) {
                int bl=1, md=w*h; for(int l=1; l<=150; l++){ int d=abs((l*l*l)-(w*h)); if(d<md){md=d; bl=l;} }
                nativeLutSize=bl; nativeLut.resize(bl*bl*bl*3); int tr=w/bl; if(tr==0) tr=1;
                for(int b=0; b<bl; b++){ int cx=b%tr, cy=b/tr; for(int g=0; g<bl; g++){ int iy=cy*bl+g; for(int r=0; r<bl; r++){ int ix=cx*bl+r; if(ix>=w)ix=w-1; if(iy>=h)iy=h-1; int s=(iy*w+ix)*3, d=(r+g*bl+b*bl*bl)*3; nativeLut[d]=id[s]; nativeLut[d+1]=id[s+1]; nativeLut[d+2]=id[s+2]; } } }
            }
            stbi_image_free(id);
        }
    } else if (ex==".cube"||ex==".cub") {
        FILE *f = fopen(fp, "r"); if(f){ char l[256]; while(fgets(l, 256, f)){ if(strncmp(l,"LUT_3D_SIZE",11)==0){ sscanf(l,"LUT_3D_SIZE %d",&nativeLutSize); nativeLut.resize(nativeLutSize*nativeLutSize*nativeLutSize*3); continue; } float r,g,b; if(nativeLutSize>0 && sscanf(l,"%f %f %f",&r,&g,&b)==3){ static size_t c=0; if(c+2<nativeLut.size()){ nativeLut[c++]=(uint8_t)(r*255); nativeLut[c++]=(uint8_t)(g*255); nativeLut[c++]=(uint8_t)(b*255); } } } fclose(f); }
    }
    env->ReleaseStringUTFChars(path, fp); return nativeLutSize>0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_LutEngine_loadGrainTextureNative(JNIEnv* env, jobject obj, jstring path) {
    nativeGrainTexture.clear(); if(!path) return JNI_FALSE; const char *fp=env->GetStringUTFChars(path, NULL); int w,h,c; unsigned char *id=stbi_load(fp,&w,&h,&c,3); env->ReleaseStringUTFChars(path,fp);
    if(id){ if((w==512||w==1024)&&w==h){ nativeGrainTexture.assign(id, id+(w*h*3)); stbi_image_free(id); return JNI_TRUE; } stbi_image_free(id); } return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_github_ma1co_pmcademo_app_LutEngine_processImageNative(JNIEnv* env, jobject obj, jstring inPath, jstring outPath, jint scaleDenom, jint opacity, jint grain, jint grainSize, jint vignette, jint rollOff, jint colorChrome, jint chromeBlue, jint shadowToe, jint subtractiveSat, jint halation, jint bloom, jint jpegQuality, jboolean applyCrop, jint numCores) {
    long long st = get_time_ms(); const char *ifn = env->GetStringUTFChars(inPath, NULL); const char *ofn = env->GetStringUTFChars(outPath, NULL);
    FILE *inf = fopen(ifn, "rb"), *ouf = fopen(ofn, "wb");
    if(!inf||!ouf){ if(inf)fclose(inf); if(ouf)fclose(ouf); env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn); return JNI_FALSE; }
    struct jpeg_decompress_struct cd; struct my_error_mgr jd; cd.err = jpeg_std_error(&jd.pub); jd.pub.error_exit = my_error_exit;
    if(setjmp(jd.setjmp_buffer)){ jpeg_destroy_decompress(&cd); fclose(inf); fclose(ouf); return JNI_FALSE; }
    jpeg_create_decompress(&cd); jpeg_stdio_src(&cd, inf); jpeg_read_header(&cd, TRUE); jpeg_save_markers(&cd, JPEG_APP0+1, 0xFFFF);
    cd.scale_denom = scaleDenom; cd.out_color_space = JCS_RGB; jpeg_start_decompress(&cd);
    std::vector<uint8_t> ex; jpeg_saved_marker_ptr m=cd.marker_list; while(m){ if(m->marker==JPEG_APP0+1){ ex.assign(m->data, m->data+m->data_length); break; } m=m->next; }
    struct jpeg_compress_struct cc; struct my_error_mgr jc; cc.err = jpeg_std_error(&jc.pub); jc.pub.error_exit = my_error_exit;
    if(setjmp(jc.setjmp_buffer)){ jpeg_destroy_compress(&cc); jpeg_destroy_decompress(&cd); fclose(inf); fclose(ouf); return JNI_FALSE; }
    jpeg_create_compress(&cc); jpeg_stdio_dest(&cc, ouf);
    int fh = cd.output_height, sk = 0; if(applyCrop){ fh=(int)(cd.output_width/2.71f); sk=(cd.output_height-fh)/2; }
    cc.image_width = cd.output_width; cc.image_height = fh; cc.input_components = 3; cc.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cc); jpeg_set_quality(&cc, jpegQuality, TRUE); jpeg_start_compress(&cc, TRUE);
    if(!ex.empty()) jpeg_write_marker(&cc, JPEG_APP0+1, ex.data(), ex.size());
    int rs = cd.output_width*3; const int CHK = 32, BUF = CHK+20;
    unsigned char* rb = (unsigned char*)malloc(BUF*rs); unsigned char* r[100]; for(int i=0; i<BUF; i++) r[i]=rb+(i*rs);
    unsigned char* ob = (unsigned char*)malloc(CHK*rs); unsigned char* orw[100]; for(int i=0; i<CHK; i++) orw[i]=ob+(i*rs);
    int map[256]; for(int i=0; i<256; i++) map[i]=(i*(nativeLutSize-1)*128)/255;
    uint8_t roll[256]; generate_rolloff_lut(roll, rollOff);
    int ts = std::min(numCores, 4), ws_s = cd.output_width*sizeof(int); std::vector<WorkerData> wks(ts);
    for(int i=0; i<ts; i++){ WorkerData& w=wks[i]; pthread_mutex_init(&w.mutex,NULL); pthread_cond_init(&w.cond_start,NULL); pthread_cond_init(&w.cond_done,NULL); w.start=w.done=w.terminate=false; w.ws.work_0=(int*)malloc(ws_s); w.ws.work_1=(int*)malloc(ws_s); w.ws.work_2=(int*)malloc(ws_s); w.ws.work_h=(int*)malloc(ws_s); w.ws.h_line=(int*)malloc(ws_s); pthread_create(&w.thread,NULL,persistent_worker_func,&w); }
    const uint8_t* tex = nativeGrainTexture.empty() ? NULL : nativeGrainTexture.data(); bool is1k = nativeGrainTexture.size()>1000000; JSAMPROW rpx[1];
    if(cd.output_height>0){ rpx[0]=r[10]; jpeg_read_scanlines(&cd,rpx,1); for(int i=0; i<10; i++) memcpy(r[i],r[10],rs); }
    for(int i=11; i<BUF; i++){ if(cd.output_scanline < cd.output_height){ rpx[0]=r[i]; jpeg_read_scanlines(&cd,rpx,1); } else memcpy(r[i],r[i-1],rs); }
    int pr = 0; while(pr < (int)cd.output_height){
        int rtp = std::min(CHK, (int)cd.output_height-pr);
        for(int i=0; i<ts; i++){ WorkerData& w=wks[i]; pthread_mutex_lock(&w.mutex); w.start_i=i*rtp/ts; w.end_i=(i+1)*rtp/ts; w.proc_rows_base=pr; w.rows=r; w.out_rows=orw; w.width=cd.output_width; w.scaleDenom=scaleDenom; w.use_rgb=(nativeLutSize>0&&opacity>0); w.bloom=bloom; w.halation=halation; w.start_time=st; w.cx=cd.output_width/2; w.cy_center=cd.output_height/2; w.vig_coef=get_vig_coef(vignette, w.cx*w.cx+w.cy_center*w.cy_center); w.shadowToe=shadowToe; w.rollOff=rollOff; w.colorChrome=colorChrome; w.chromeBlue=chromeBlue; w.subSat=subtractiveSat; w.grain=grain; w.grainSize=grainSize; w.opac_m=(opacity*256)/100; w.map=map; w.roll_lut=roll; w.extTex=tex; w.is_1024=is1k; w.applyCrop=applyCrop; w.skip_top=sk; w.final_h=fh; w.start=true; w.done=false; pthread_cond_signal(&w.cond_start); pthread_mutex_unlock(&w.mutex); }
        for(int i=0; i<ts; i++){ WorkerData& w=wks[i]; pthread_mutex_lock(&w.mutex); while(!w.done) pthread_cond_wait(&w.cond_done,&w.mutex); pthread_mutex_unlock(&w.mutex); }
        for(int i=0; i<rtp; i++){ int ay=pr+i; if(!applyCrop||(ay>=sk && ay<sk+fh)){ rpx[0]=orw[i]; jpeg_write_scanlines(&cc,rpx,1); } }
        unsigned char* tmpx[100]; for(int i=0; i<rtp; i++) tmpx[i]=r[i]; for(int i=0; i<BUF-rtp; i++) r[i]=r[i+rtp];
        for(int i=0; i<rtp; i++){ int di=BUF-rtp+i; r[di]=tmpx[i]; if(cd.output_scanline<cd.output_height){ rpx[0]=r[di]; jpeg_read_scanlines(&cd,rpx,1); } else memcpy(r[di],r[di-1],rs); }
        pr += rtp;
    }
    for(int i=0; i<ts; i++){ WorkerData& w=wks[i]; pthread_mutex_lock(&w.mutex); w.terminate=true; pthread_cond_signal(&w.cond_start); pthread_mutex_unlock(&w.mutex); pthread_join(w.thread,NULL); free(w.ws.work_0); free(w.ws.work_1); free(w.ws.work_2); free(w.ws.work_h); free(w.ws.h_line); }
    free(rb); free(ob); jpeg_finish_compress(&cc); jpeg_destroy_compress(&cc); jpeg_finish_decompress(&cd); jpeg_destroy_decompress(&cd); fclose(inf); fclose(ouf); env->ReleaseStringUTFChars(inPath,ifn); env->ReleaseStringUTFChars(outPath,ofn); return JNI_TRUE;
}
