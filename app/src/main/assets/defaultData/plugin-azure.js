let key = 'KEY'
let region = 'eastus'

let format = "audio-24khz-48kbitrate-mono-mp3"
let sampleRate = 24000 // 对应24khz. 格式后带有opus的实际采样率是其2倍

let PluginJS = {
    "name": "Azure",
    "id": "com.microsoft.azure",
    "author": "TTS Server",
    "description": "",
    "version": 2,

    "getAudio": function (text, locale, voice, rate, volume, pitch) {
        rate = (rate * 2) - 100
        pitch = pitch - 50

        let styleDegree = ttsrv.tts.data['styleDegree']
        if (!styleDegree || Number(styleDegree) < 0.01) {
            styleDegree = '1.0'
        }

        let style = ttsrv.tts.data['style']
        let role = ttsrv.tts.data['role']
        if (!style || style === "") {
            style = 'general'
        }
        if (!role || role === "") {
            role = 'default'
        }

        let ssml = `
        <speak xmlns="http://www.w3.org/2001/10/synthesis" xmlns:mstts="http://www.w3.org/2001/mstts" xmlns:emo="http://www.w3.org/2009/10/emotionml" version="1.0" xml:lang="zh-CN">
            <voice name="${voice}">
                <mstts:express-as style="${style}" styledegree="${styleDegree}" role="${role}">
                    <prosody rate="${rate}%" pitch="${pitch}%" volume="${volume}">${text}</prosody>
                </mstts:express-as>
            </voice >
         </speak >
        `

        logger.d(ssml)
        return getAudioInternal(ssml, format)
    },
}

let ttsUrl = 'https://' + region + '.tts.speech.microsoft.com/cognitiveservices/v1'
function getAudioInternal(ssml, format) {
    let headers = {
        'Ocp-Apim-Subscription-Key': key,
        "X-Microsoft-OutputFormat": format,
        "Content-Type": "application/ssml+xml",
    }
    let resp = ttsrv.httpPost(ttsUrl, ssml, headers)
    if (resp.code() !== 200) {
        throw "音频获取失败: HTTP-" + resp.code()
    }

    return resp.body().bytes()
}

// 全部voice数据
let voices = {}
// 当前语言下的voice
let currentVoices = new Map()

let styleSpinner
let roleSpinner
let seekStyle

let EditorJS = {
    //音频的采样率 编辑TTS界面保存时调用
    "getAudioSampleRate": function (locale, voice) {
        // 根据voice判断返回的采样率
        // 也可以动态获取：
        return sampleRate
    },

    "getLocales": function () {
        let locales = new Array()

        voices.forEach(function (v) {
            let loc = v["Locale"]
            if (!locales.includes(loc)) {
                locales.push(loc)
            }
        })

        return locales
    },

    // 当语言变更时调用
    "getVoices": function (locale) {
        currentVoices = new Map()
        voices.forEach(function (v) {
            if (v['Locale'] === locale) {
                currentVoices.set(v['ShortName'], v)
            }
        })

        let mm = {}
        for (let [key, value] of currentVoices.entries()) {
            mm[key] = new java.lang.String(value['LocalName'] + ' (' + key + ')')
        }
        return mm
    },

    // 加载本地或网络数据，运行在IO线程。
    "onLoadData": function () {
        // 获取数据并缓存以便复用
        let jsonStr = ''
        if (ttsrv.fileExist('voices.json')) {
            jsonStr = ttsrv.readTxtFile('voices.json')
        } else {
            let url = 'https://' + region + '.tts.speech.microsoft.com/cognitiveservices/voices/list'
            let header = {
                "Ocp-Apim-Subscription-Key": key,
                "Content-Type": "application/json",
            }
            jsonStr = ttsrv.httpGetString(url, header)
            ttsrv.writeTxtFile('voices.json', jsonStr)
        }

        voices = JSON.parse(jsonStr)
    },

    "onLoadUI": function (ctx, linerLayout) {
        let layout = new LinearLayout(ctx)
        layout.orientation = LinearLayout.HORIZONTAL // 水平布局

        styleSpinner = JSpinner(ctx, "风格 (style)")
        let params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1)
        styleSpinner.layoutParams = params
        layout.addView(styleSpinner)
        ttsrv.setMargins(styleSpinner, 2, 4, 0, 0)
        styleSpinner.setOnItemSelected(function (spinner, pos, item) {
            ttsrv.tts.data['style'] = item.value
            // 默认 || value为空 || value空字符串
            if (pos === 0 || !item.value || item.value === "") {
                seekStyle.visibility = View.GONE // 移除风格强度
            } else {
                seekStyle.visibility = View.VISIBLE // 显示
            }
        })

        roleSpinner = JSpinner(ctx, "角色 (role)")
        roleSpinner.layoutParams = params
        layout.addView(roleSpinner)
        ttsrv.setMargins(roleSpinner, 0, 4, 2, 0)
        roleSpinner.setOnItemSelected(function (spinner, pos, item) {
            ttsrv.tts.data['role'] = item.value
        })
        linerLayout.addView(layout)

        seekStyle = JSeekBar(ctx, "风格强度 (Style degree)：")
        linerLayout.addView(seekStyle)
        ttsrv.setMargins(seekStyle, 0, 4, 0, -4)
        seekStyle.setFloatType(2) // 二位小数
        seekStyle.max = 200 //最大200个刻度

        let styleDegree = Number(ttsrv.tts.data['styleDegree'])
        if (!styleDegree || isNaN(styleDegree)) {
            styleDegree = 1.0

        }
        seekStyle.value = new java.lang.Float(styleDegree)

        seekStyle.setOnChangeListener(
            {
                // 开始时
                onStartTrackingTouch: function (seek) {

                },
                // 进度滑动更改时
                onProgressChanged: function (seek, progress, fromUser) {

                },
                // 停止时
                onStopTrackingTouch: function (seek) {
                    ttsrv.tts.data['styleDegree'] = Number(seek.value).toFixed(2)
                },
            }
        )
    },

    "onVoiceChanged": function (locale, voiceCode) {
        let vic = currentVoices.get(voiceCode)

        let styles = vic['StyleList']
        let styleItems = []
        let stylePos = 0
        if (styles) {
            styleItems.push(Item("默认 (general)", ""))
            styles.map(function (v, i) {
                styleItems.push(Item(v, v))
                if (v === ttsrv.tts.data['style'] + '') {
                    stylePos = i + 1 //算上默认的item 所以要 +1
                }
            })
        } else {
            seekStyle.visibility = View.GONE
        }
        styleSpinner.items = styleItems
        styleSpinner.selectedPosition = stylePos

        let roles = vic['RolePlayList']
        let roleItems = []
        let rolePos = 0
        if (roles) {
            roleItems.push(Item("默认 (default)", ""))
            roles.map(function (v, i) {
                roleItems.push(Item(v, v))
                if (v === ttsrv.tts.data['role'] + '') {
                    rolePos = i + 1 //算上默认的item 所以要 +1
                }
            })
        }
        roleSpinner.items = roleItems
        roleSpinner.selectedPosition = rolePos
    }

}

