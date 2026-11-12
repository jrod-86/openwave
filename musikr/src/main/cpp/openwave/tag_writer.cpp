/*
 * Copyright (c) 2026 Auxio Project
 * tag_writer.cpp is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
#include <jni.h>
#include <unistd.h>
#include "../util.h"

#include "taglib/fileref.h"
#include "taglib/tfilestream.h"
#include "taglib/tpropertymap.h"
#include "taglib/tvariant.h"

// Openwave: writes tags and front cover artwork into an audio file.

static TagLib::String toTagLibString(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    TagLib::String result {chars, TagLib::String::UTF8};
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_oxycblt_musikr_metadata_TagLibJNI_writeNative(JNIEnv *env,
        jobject /* this */,
        jint fd,
        jobjectArray keys,
        jobjectArray values,
        jbyteArray picture,
        jstring pictureMimeType) {
    try {
        // The stream takes ownership of the file descriptor and closes it when destroyed.
        // It must outlive the FileRef, so it is declared first.
        TagLib::FileStream stream {fd, false};
        if (!stream.isOpen() || stream.readOnly()) {
            LOGE("Unable to open file descriptor %d for writing", fd);
            if (!stream.isOpen()) {
                close(fd);
            }
            return JNI_FALSE;
        }
        TagLib::FileRef fileRef {&stream, false};
        if (fileRef.isNull()) {
            LOGE("Unable to detect file type for writing");
            return JNI_FALSE;
        }
        TagLib::PropertyMap properties = fileRef.properties();
        jsize count = env->GetArrayLength(keys);
        for (jsize i = 0; i < count; i++) {
            auto key = (jstring) env->GetObjectArrayElement(keys, i);
            auto value = (jstring) env->GetObjectArrayElement(values, i);
            TagLib::String tagKey = toTagLibString(env, key);
            TagLib::String tagValue = toTagLibString(env, value);
            if (tagValue.isEmpty()) {
                properties.erase(tagKey);
            } else {
                properties.replace(tagKey, TagLib::StringList(tagValue));
            }
            env->DeleteLocalRef(key);
            env->DeleteLocalRef(value);
        }
        fileRef.setProperties(properties);
        if (picture != nullptr) {
            jsize size = env->GetArrayLength(picture);
            jbyte *bytes = env->GetByteArrayElements(picture, nullptr);
            TagLib::ByteVector data {reinterpret_cast<const char*>(bytes),
                    static_cast<unsigned int>(size)};
            env->ReleaseByteArrayElements(picture, bytes, JNI_ABORT);
            TagLib::VariantMap pictureMap;
            pictureMap["data"] = data;
            pictureMap["mimeType"] = toTagLibString(env, pictureMimeType);
            pictureMap["pictureType"] = TagLib::String("Front Cover");
            pictureMap["description"] = TagLib::String("");
            fileRef.setComplexProperties("PICTURE", {pictureMap});
        }
        return fileRef.save() ? JNI_TRUE : JNI_FALSE;
    } catch (std::exception &e) {
        LOGE("Unable to write tags: %s", e.what());
        return JNI_FALSE;
    }
}
