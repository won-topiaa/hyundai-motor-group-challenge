# Calm Ride — 전기차 멀미를 소리로 줄이는 차량용 앱

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작입니다.

Calm Ride는 두 가지 소리로 동승자의 멀미를 줄입니다.
- **출발 전:** 정차 중에 100Hz 소리를 1분 들려줘 평형감각을 준비시킵니다.
- **주행 중:** 내비게이션이 아는 회전·방지턱 직전에 짧은 예고음을 들려줘 움직임을 미리 알 수 있게 합니다.

운전자마다 주행 기록을 정해진 규칙으로 분석해, 예고 시점과 코칭을 그 사람에게 맞춥니다.

회생제동 감속은 가속 페달 신호가 SDK에 없어 미리 알릴 수 없습니다. 이 부분은 경로 예고와 회생제동 코칭으로 대응합니다.

## 주요 기능

| 기능 | 설명 | 사용하는 SDK·API |
| --- | --- | --- |
| 출발 전 1분 사운드 | 정차 중에만 100Hz 순음을 1분 재생합니다. 일상 소음 수준이며, 시작과 끝은 서서히 커지고 작아집니다. | Android `AudioTrack`, AAOS `GEAR_SELECTION`·`PERF_VEHICLE_SPEED` |
| 회전·방지턱 예고음 | 다음 회전과 방지턱에 도달하기 1~3초 전에 회전 방향 쪽 스피커에서 울립니다. | Pleos NaviHelper `onTBTInfo`(`distance`), `onCautionPointInfo`(`remainDistance`), `onCameraAlert` |
| 감속·차선 변경 알림 | 브레이크를 밟거나 방향지시등을 켜는 순간 울립니다. 브레이크 알림은 감속과 거의 동시라 예고가 아닌 보조 알림입니다. 모든 알림은 1분에 최대 6번입니다. | Pleos Vehicle SDK `registerBrakePedalStatus`, `registerTurnSignalState` |
| 환기 | 멀미 모드에서 외기를 들이고 풍량을 높였다가, 끄면 원래대로 돌립니다. | Pleos Vehicle SDK `setHvacRecirculation`, `setHvacFanSpeed` |
| 뒷좌석 탑승 감지 | 뒷좌석에 사람이 타면 멀미 모드를 먼저 제안합니다. | Pleos Vehicle SDK `registerSeatOccupancy` |
| 운전자별 맞춤 (규칙 기반) | 제동 시점, 회생제동 감속, 차선 변경 습관을 정해진 규칙으로 판별해 운전 스타일을 분류합니다. 이를 바탕으로 예고 시점을 조정하고 코칭 문장을 하나씩 제안합니다. | Vehicle SDK(조향각·브레이크), AAOS(차속·회생제동), FusedLocation(급감속 지점) |
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
./gradlew :app:testDebugUnitTest   # 단위 테스트 21개
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

## Pleos Connect 에뮬레이터 검증 결과 (2026-11-04)

Pleos Connect Emulator v2.1.0(API 34)과 Virtual Console로 확인했습니다. 실제 신호 항목은 `dumpsys car_service`의 오디오 포커스 로그로 확인했습니다.

| 항목 | 결과 |
| --- | --- |
| SDK 연결 | Vehicle SDK, AAOS Car API, NaviHelper, FusedLocation, TextToSpeech 모두 연결됨 |
| 권한 | `CONTROL_CAR_CLIMATE`를 포함한 모든 권한이 일반 앱에 허용됨 |
| 차량 신호 | 기어(AAOS `GEAR_SELECTION`), 차속, 브레이크, 방향지시등, 뒷좌석 탑승, 회생제동 상태가 앱에 표시됨 |
| 뒷좌석 탑승 제안 | 뒷좌석 탑승을 감지하면 멀미 모드 제안 카드가 뜸 |
| 환기 | 멀미 모드에서 풍량 3→5, 내기순환 꺼짐(외기 유입) 확인 |
| 1분 사운드 | 차량 오디오 서비스가 `USAGE_MEDIA`·`GAIN_TRANSIENT` 포커스를 허락했고 카운트다운이 동작함 |
| 주행 중 화면 | 기어 D에서 시스템이 앱 화면을 "주행 중에는 사용할 수 없습니다"로 막고, 포그라운드 서비스는 계속 동작함 |
| 감속·차선 알림 (실제 신호) | Virtual Console로 기어 D에서 가속하다 브레이크·방향지시등을 조작하자 알림음이 `GAIN_TRANSIENT_MAY_DUCK`으로 허락받아 재생됨. 주행 후 화면에 "최근 알림음: 감속 알림 (브레이크 작동)" 표시 |
| 경로 예고음 (실제 경로 안내) | 에뮬레이터 내비게이션 앱으로 경로 안내를 시작하자 앱이 NaviHelper `GetTBTInfo`로 회전·도착 정보를 받고, 도달 시간이 예고 시점 안으로 들어오자 예고음 재생. GPS가 고정돼 차속만 올려 시험했으므로 실제 접근 상황은 아님 |
| 알림 상한 | 1분에 6번을 채우면 다음 알림이 막히고, 60초가 지나자 다시 재생 |
| 자원 사용 (주행 중) | 메모리 PSS 58.8MB, CPU 기기 전체의 0.5~1.4% (`-accel off` 환경이라 실제보다 높게 나옴). 기준은 CPU 5% 미만, RAM 400MB 이하 |
| 데모 주행 예고음 | 데모 주행에서 예고음 4번이 모두 `GAIN_TRANSIENT_MAY_DUCK`으로 포커스를 허락받아 재생됨 (예: "좌회전 9m 앞") |
| 운전자 맞춤 | 주행 종료(D→P) 후 컨디션 질문, 코칭 표시, 예고 시점 1.5→1.7초 조정, 프로필 저장 확인 |

검증 중에 찾아서 고친 버그도 있습니다.
- **차량 서비스 재시작 시 앱 종료:** `Car.createCar()`를 생명주기 리스너 없이 호출하면, 차량 서비스가 재시작될 때 Android가 앱을 강제 종료했습니다. → 리스너 방식으로 바꿔 재연결하도록 했습니다.
- **내기순환 설정 실패:** 풍량용 구역 ID(0x5)로 내기순환을 설정해서 실패했습니다. → 기능마다 자기 구역을 조회하도록 바꿨습니다.
- **코칭 문구 모순:** 컨디션이 낮은데도 "부드럽게 운전하셨어요… 예고음을 줄일게요"가 나왔습니다. → 문구와 예고음 설정을 바로잡았습니다.
- **경로 안내 거리 계산:** NaviHelper TBT 목록에서 두 번째 항목부터는 앞 안내 지점에서 잰 거리인데(예: 좌회전 82m, 좌회전 208m, 도착 58m), 모두 차에서부터의 거리로 읽었습니다. 그래서 도착 예고음이 회전보다 먼저 울렸습니다. → 거리를 누적하도록 고쳤습니다. 수정 후 에뮬레이터 재확인은 아직 하지 않았습니다.

스크린샷은 [`docs/screenshots/`](docs/screenshots/)에 있습니다. `06`은 실제 브레이크 신호로 울린 감속 알림, `07`은 실제 경로 안내를 받는 화면입니다. `07`의 "도착 50m"는 위 거리 계산 버그가 고쳐지기 전의 화면입니다.

### 아직 확인하지 못한 것

- [ ] GPS가 실제로 움직일 때 회전 직전 예고 시점이 맞는지, `onCautionPointInfo`(과속방지턱)가 들어오는지. 에뮬레이터는 GPS가 고정되고 시험 경로에 방지턱이 없었습니다.
- [ ] 경로 안내 거리 수정 후 에뮬레이터 재확인
- [ ] 차량 스피커에서 좌우 패닝이 들리는지 (실차)
- [ ] 공조 전원이 꺼져 있을 때는 풍량 설정이 거부됨 (`HVAC_FAN_SPEED` NOT_AVAILABLE). 전원을 켤지 결정이 필요합니다.
- [ ] SpeechToText 음성 명령 (에뮬레이터 마이크 설정 필요)

## 문서

기획 과정의 조사 자료는 [`docs/`](docs/)에 있습니다.

| 문서 | 내용 |
| --- | --- |
| [01_공모전_요강.md](docs/01_공모전_요강.md) | 일정, 제출물, 심사 기준, 시상 |
| [02_기획서/Calm_Ride_기획서.docx](docs/02_기획서/Calm_Ride_기획서.docx) | 기획서 |
| [03_리서치/](docs/03_리서치/) | 택시 기사의 어려움, 전기차 멀미 원인과 근거 연구 |
| [04_Pleos_SDK_조사.md](docs/04_Pleos_SDK_조사.md) | SDK API 조사 |
| [05_구현_가능성_조사.md](docs/05_구현_가능성_조사.md) | 기능별 구현 가능 여부와 플랫폼 규칙 |
| [06_자체_검증_계획.md](docs/06_자체_검증_계획.md) | 설문 문항과 실차 파일럿 절차, 파일럿용 예고음 WAV([`pilot_sounds/`](docs/pilot_sounds/)) |

## 근거 연구

- Gu et al. (2025), *Just 1-min exposure to a pure tone at 100 Hz … may improve motion sickness*, Environmental Health and Preventive Medicine — [나고야대 보도자료](https://en.nagoya-u.ac.jp/news/articles/research_information_267/)
- Kuiper et al. (2020), *Knowing what's coming: Anticipatory audio cues can mitigate motion sickness*, Applied Ergonomics 85 — [논문 정보](https://research.vu.nl/en/publications/knowing-whats-coming-anticipatory-audio-cues-can-mitigate-motion-/)

Calm Ride는 멀미를 '치료'하는 앱이 아니라 '완화를 돕는' 앱입니다.
