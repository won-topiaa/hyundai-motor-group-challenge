# Calm Ride — 전기차 멀미를 소리로 줄이는 차량용 앱

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작입니다.

Calm Ride는 두 가지 소리로 동승자의 멀미를 줄입니다.
- **출발 전:** 정차 중에 100Hz 소리를 1분 들려줘 평형감각을 준비시킵니다.
- **주행 중:** 회전·감속 직전에 짧은 예고음을 들려줘 움직임을 미리 알 수 있게 합니다.

운전자마다 운전 습관을 학습해서, 예고 시점과 코칭을 그 사람에게 맞춥니다.

## 주요 기능

| 기능 | 설명 | 사용하는 SDK·API |
| --- | --- | --- |
| 출발 전 1분 사운드 | 정차 중에만 100Hz 순음을 1분 재생합니다. 일상 소음 수준이며, 시작과 끝은 서서히 커지고 작아집니다. | Android `AudioTrack`, AAOS `GEAR_SELECTION`·`PERF_VEHICLE_SPEED` |
| 회전·방지턱 예고음 | 다음 회전과 방지턱에 도달하기 1~2초 전에 회전 방향 쪽 스피커에서 울립니다. | Pleos NaviHelper `onTBTInfo`(`distance`), `onCautionPointInfo`(`remainDistance`), `onCameraAlert` |
| 감속·차선 변경 예고음 | 브레이크를 밟거나 방향지시등을 켜는 순간 울립니다. | Pleos Vehicle SDK `registerBrakePedalStatus`, `registerTurnSignalState` |
| 환기 | 멀미 모드에서 외기를 들이고 풍량을 높였다가, 끄면 원래대로 돌립니다. | Pleos Vehicle SDK `setHvacRecirculation`, `setHvacFanSpeed` |
| 뒷좌석 탑승 감지 | 뒷좌석에 사람이 타면 멀미 모드를 먼저 제안합니다. | Pleos Vehicle SDK `registerSeatOccupancy` |
| 운전자 맞춤 에이전트 | 제동 시점, 회생제동 감속, 차선 변경 습관을 학습해 운전 스타일을 분류합니다. 이를 바탕으로 예고 시점을 조정하고 코칭 문장을 하나씩 제안합니다. | Vehicle SDK(조향각·브레이크), AAOS(차속·회생제동), FusedLocation(급감속 지점) |
| 운전자 자동 인식 | 시트 위치(앞뒤·높이)로 누가 운전하는지 알아봅니다. | Pleos Vehicle SDK `getCurrentSeatForeAftPosition`, `getCurrentSeatHeightPosition` |
| 음성 안내·명령 | "멀미 모드 켜줘", "나 지우야" 같은 음성 명령을 받고, 모드 안내와 코칭을 읽어 줍니다. | Pleos TextToSpeech / SpeechToText (ON_DEVICE) |
| 데모 주행 | 차량 없이 70초 주행(좌회전, 방지턱, 차선 변경, 단속 구간, 도착)을 재생해 예고음을 확인합니다. | — |

## 구조

```
app/src/main/java/com/calmride/
├── core/        순수 Kotlin 로직 (단위 테스트 대상)
│   ├── CueEngine        예고 시점 계산 (도달 시간 = 남은 거리 ÷ 차속)
│   ├── ToneSynth        100Hz 순음·예고음 PCM 생성, 좌우 패닝
│   ├── TripRecorder     주행 중 급감속·회생 감속·늦은 제동·빠른 차선 변경 기록
│   ├── DriverProfile    운전 스타일 분류(StyleClassifier), 예고 시점 조정(ProfileTuner), 시트 기반 인식
│   ├── CoachingEngine   문장 템플릿 기반 코칭 (습관 하나만, 지적하지 않기)
│   └── StopDetector, VoiceCommandParser
├── vehicle/     Pleos Vehicle·NaviHelper·FusedLocation SDK, AAOS Car API 연동, 데모 주행
├── voice/       Pleos TextToSpeech·SpeechToText
├── audio/       오디오 포커스 정책을 지키는 재생 (예고음은 MAY_DUCK)
├── runtime/     CalmRideController(전체 연결), CalmRideService(포그라운드 서비스)
├── data/        운전자 프로필 저장 (차량 내부에만 저장)
└── ui/          Jetpack Compose 화면
```

## 빌드

필요한 환경은 JDK 17, Android SDK(Platform 35, Build-Tools 35)예요. Pleos SDK는 공개 Maven 저장소(`https://nexus-playground.pleos.ai/repository/maven-releases/`)에서 받아요.

```bash
./gradlew :app:testDebugUnitTest   # 단위 테스트 19개
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

빌드된 APK는 [`apk/CalmRide_release.apk`](apk/CalmRide_release.apk)에 있습니다.
- 공모전 제출 규칙에 맞추려면 파일 이름을 `팀명_CalmRide_release.apk`로 바꿔 주세요.
- 이 APK는 데모용 디버그 키로 서명돼 있습니다. App Market에 등록할 때는 실제 키로 다시 서명해야 합니다.

## Pleos Connect 에뮬레이터에서 실행

1. Android Studio에 Pleos Connect Emulator v2.1.0(API 34)을 설치하고 AVD를 만듭니다. 부팅 옵션은 Cold boot로 설정합니다. ([설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator))
2. `adb install -r apk/CalmRide_release.apk`로 설치하고 앱을 실행합니다. 차속·회생제동 권한 요청은 허용합니다.
3. `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`에서 Virtual Console을 엽니다.
4. 다음 순서로 시연합니다.
   - Seat Occupancy에서 뒷좌석을 Occupied로 바꿉니다. 멀미 모드 제안이 떠요.
   - 멀미 모드를 켜고, 기어 P에서 '1분 사운드 시작'을 누릅니다.
   - 기어를 D로 바꾼 뒤 Brake Pedal과 Turn Signal을 조작하면 감속·차선 변경 예고음이 울려요.
   - 기어를 P로 돌리면 동승자 컨디션을 묻고, 맞춤 코칭이 표시돼요.
5. 회전·방지턱 예고는 내비게이션 경로 안내 중에 동작합니다. 차량 없이 확인하려면 앱의 '데모 주행 시작'을 누릅니다.
6. 주행 중 화면 제한을 시험하려면 다음 명령을 씁니다. `adb shell cmd hmg_car_service --service drivingstate driving_regulation on`

## 플랫폼 규칙 준수

- **주행 최적화(DO):** 선언하지 않았습니다. 주행 중에는 화면 대신 포그라운드 서비스가 소리와 음성으로만 안내합니다. `CarUxRestrictionsManager`로 제한 상태도 확인합니다.
- **오디오 포커스:** 예고음은 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`(음악을 줄이고 함께 재생), 100Hz 사운드는 `AUDIOFOCUS_GAIN_TRANSIENT`로 요청합니다. 포커스를 잃으면 즉시 멈춥니다.
- **차속·기어:** Pleos 정책(AAOS Alignment)에 따라 AAOS `PERF_VEHICLE_SPEED`, `GEAR_SELECTION`으로 읽습니다.
- **회생제동:** 단계는 읽기만 합니다. 바꾸는 권한(`CONTROL_CAR_POWERTRAIN`)은 시스템 전용이라 추천만 합니다.
- **개인정보:** 운전자 프로필과 주행 기록은 차량 내부(SharedPreferences)에만 저장하고 외부로 보내지 않습니다.

## 아직 확인하지 못한 것

빌드, 단위 테스트, Lint까지는 통과했습니다. 다만 아직 **실제 Pleos 에뮬레이터나 실차에서 실행해 보지는 못했습니다.** 다음 항목은 에뮬레이터에서 확인이 필요합니다.

- [ ] Vehicle SDK 리스너 등록 시점 (서비스 연결 후 재시도 로직 동작 여부)
- [ ] 공조 제어 권한(`CONTROL_CAR_CLIMATE`)이 일반 앱에 허용되는지
- [ ] NaviHelper `onTBTInfo`가 자동으로 오는지 (현재는 1초마다 `getTBTInfo()`도 호출)
- [ ] 에뮬레이터에서 차속·회생제동 값이 나오는지
- [ ] 차량 스피커에서 좌우 패닝이 들리는지 (실차)
- [ ] TTS/STT ON_DEVICE 모드가 앱 등록 없이 동작하는지

## 문서

기획 과정의 조사 자료는 [`docs/`](docs/)에 있습니다.

| 문서 | 내용 |
| --- | --- |
| [01_공모전_요강.md](docs/01_공모전_요강.md) | 일정, 제출물, 심사 기준, 시상 |
| [02_기획서/Calm_Ride_기획서_초안.docx](docs/02_기획서/Calm_Ride_기획서_초안.docx) | 기획서 초안 |
| [03_리서치/](docs/03_리서치/) | 택시 기사의 어려움, 전기차 멀미 원인과 근거 연구 |
| [04_Pleos_SDK_조사.md](docs/04_Pleos_SDK_조사.md) | SDK API 조사 |
| [05_구현_가능성_조사.md](docs/05_구현_가능성_조사.md) | 기능별 구현 가능 여부와 플랫폼 규칙 |

## 근거 연구

- Gu et al. (2025), *Just 1-min exposure to a pure tone at 100 Hz … may improve motion sickness*, Environmental Health and Preventive Medicine — [나고야대 보도자료](https://en.nagoya-u.ac.jp/news/articles/research_information_267/)
- Kuiper et al. (2020), *Knowing what's coming: Anticipatory audio cues can mitigate motion sickness*, Applied Ergonomics 85 — [논문 정보](https://research.vu.nl/en/publications/knowing-whats-coming-anticipatory-audio-cues-can-mitigate-motion-/)

Calm Ride는 멀미를 '치료'하는 앱이 아니라 '완화를 돕는' 앱입니다.
