#include <jni.h>
#include <algorithm>
#include <new>
#include "rnnoise/rnnoise.h"

namespace {
struct Handle {
  RNNModel *model;
  DenoiseState *state;
};

void throwState(JNIEnv *env, const char *message) {
  jclass type=env->FindClass("java/lang/IllegalStateException");
  if(type)env->ThrowNew(type,message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_qwen_tts_android_audio_RnnoiseNative_create(JNIEnv *env, jclass, jstring path) {
  if(!path){throwState(env,"RNNoise model path is missing");return 0;}
  const char *raw=env->GetStringUTFChars(path,nullptr);
  if(!raw)return 0;
  RNNModel *model=rnnoise_model_from_filename(raw);
  env->ReleaseStringUTFChars(path,raw);
  if(!model){throwState(env,"RNNoise model could not be loaded");return 0;}
  DenoiseState *state=rnnoise_create(model);
  if(!state){rnnoise_model_free(model);throwState(env,"RNNoise model is invalid");return 0;}
  Handle *handle=new(std::nothrow) Handle{model,state};
  if(!handle){rnnoise_destroy(state);rnnoise_model_free(model);throwState(env,"RNNoise state allocation failed");return 0;}
  return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_qwen_tts_android_audio_RnnoiseNative_process(JNIEnv *env, jclass, jlong pointer, jfloatArray input, jfloatArray output) {
  Handle *handle=reinterpret_cast<Handle *>(pointer);
  const int frame=rnnoise_get_frame_size();
  if(!handle||!handle->state||!input||!output||env->GetArrayLength(input)!=frame||env->GetArrayLength(output)!=frame){
    throwState(env,"RNNoise frame is invalid");return;
  }
  float in[480],out[480];
  env->GetFloatArrayRegion(input,0,frame,in);
  if(env->ExceptionCheck())return;
  for(int i=0;i<frame;i++)in[i]=std::max(-1.0f,std::min(1.0f,in[i]))*32768.0f;
  rnnoise_process_frame(handle->state,out,in);
  for(int i=0;i<frame;i++)out[i]=std::max(-1.0f,std::min(1.0f,out[i]/32768.0f));
  env->SetFloatArrayRegion(output,0,frame,out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_qwen_tts_android_audio_RnnoiseNative_destroy(JNIEnv *, jclass, jlong pointer) {
  Handle *handle=reinterpret_cast<Handle *>(pointer);
  if(!handle)return;
  if(handle->state)rnnoise_destroy(handle->state);
  if(handle->model)rnnoise_model_free(handle->model);
  delete handle;
}
