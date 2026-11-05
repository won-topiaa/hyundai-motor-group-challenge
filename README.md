# Calm Ride — 전기차 멀미를 소리로 줄이는 차량용 앱

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작입니다.

Calm Ride는 두 가지 소리로 동승자의 멀미를 줄입니다.
- **출발 전:** 정차 중에 100Hz 소리를 1분 들려줘 평형감각을 준비시킵니다. 근거는 [Gu 외, 2025](https://pmc.ncbi.nlm.nih.gov/articles/PMC11955832/)이고, 이 연구의 조건(양쪽 귀에 같은 크기, 끊김 없는 1분)을 지키도록 만들었습니다.
- **주행 중:** 내비게이션이 아는 회전·방지턱 직전에 짧은 예고음을 들려줘 움직임을 미리 알 수 있게 합니다.

운전자마다 주행 기록을 정해진 규칙으로 분석해, 예고 시점과 코칭을 그 사람에게 맞춥니다.

회생제동 감속은 가속 페달 신호가 SDK에 없어 미리 알릴 수 없습니다. 이 부분은 경로 예고와 회생제동 코칭으로 대응합니다.

## 주요 기능

| 기능 | 설명 | 사용하는 SDK·API |
| --- | --- | --- |
| 출발 전 1분 사운드 | 정차 중에만 100Hz 순음을 1분 재생합니다. 일상 소음 수준이며, 시작과 끝은 서서히 커지고 작아집니다. 차가 움직이면 0.4초에 걸쳐 서서히 끕니다. | Android `AudioTrack`, AAOS `GEAR_SELECTION`·`PERF_VEHICLE_SPEED` |
| 앉은 자리 좌우 보정 | 뒷좌석 한쪽에 앉으면 먼 쪽 스피커의 100Hz를 조금 키워 양쪽 귀에 같은 크기로 들리게 합니다. 기본 2dB는 임시값이고, 실차에서 소음계로 재서 1dB 단위로 맞춥니다. | Pleos Vehicle SDK `registerSeatOccupancy`·`registerSeatBeltBuckledState` (뒷좌석 왼쪽·가운데·오른쪽) |
| 신호 대기 때 다시 듣기 (택시, 선택) | 켜 두면 주행 중 3초 넘게 정차할 때 100Hz를 다시 틀고, 출발하면 서서히 끕니다. 1분을 끊김 없이 다 들어야 완료로 치고, 끊기면 다음 정차 때 처음부터 다시 듣습니다. 이동 중 재생은 연구된 적이 없어 효과가 확인되지 않은 가설입니다. | AAOS `PERF_VEHICLE_SPEED`, 음성 "신호에서 소리 들려줘" |
| 회전·방지턱 예고음 | 다음 회전과 방지턱에 도달하기 1~3초 전에 회전 방향 쪽 스피커에서 울립니다. | Pleos NaviHelper `onTBTInfo`(`distance`), `onCautionPointInfo`(`remainDistance`), `onCameraAlert` |
| 감속·차선 변경 알림 | 브레이크를 밟거나 방향지시등을 켜는 순간 울립니다. 브레이크 알림은 감속과 거의 동시라 예고가 아닌 보조 알림입니다. 모든 알림은 1분에 최대 6번입니다. | Pleos Vehicle SDK `registerBrakePedalStatus`, `registerTurnSignalState` |
| 환기 | 멀미 모드에서 외기를 들이고 풍량을 높였다가, 끄면 원래대로 돌립니다. | Pleos Vehicle SDK `setHvacRecirculation`, `setHvacFanSpeed` |
| 뒷좌석 탑승 감지 | 뒷좌석에 사람이 타면 멀미 모드를 먼저 제안합니다. 탑승 센서가 없는 차를 위해 안전벨트 체결도 탑승으로 봅니다. | Pleos Vehicle SDK `registerSeatOccupancy`, `registerSeatBeltBuckledState` |
| 운전자별 맞춤 (규칙 기반) | 제동 시점, 회생제동 감속, 차선 변경, 출발 가속, 회전 속도, 방지턱 통과 속도를 정해진 규칙으로 판별해 운전 스타일 7가지 중 하나로 분류합니다. 이를 바탕으로 예고 시점을 조정하고 코칭 문장을 하나씩 제안합니다. | Vehicle SDK(조향각·브레이크), AAOS(차속·회생제동), FusedLocation(급감속 지점) |
| 운전자 자동 인식 | 시트 위치(앞뒤·높이)로 누가 운전하는지 알아봅니다. | Pleos Vehicle SDK `getCurrentSeatForeAftPosition`, `getCurrentSeatHeightPosition` |
| 음성 안내·명령 | "멀미 모드 켜줘", "나 지우야" 같은 음성 명령을 받고, 모드 안내와 코칭을 읽어 줍니다. | Pleos TextToSpeech / SpeechToText (ON_DEVICE) |
| 데모 주행 | 차량 없이 80초 주행(좌회전, 방지턱, 차선 변경, 단속 구간, 도착)을 재생해 예고음을 확인합니다. 급출발·급회전·방지턱 과속을 고르면 110초짜리 거친 주행을 재생하고, 주행이 끝나면 그 운전 스타일로 판별됩니다. | — |

## 구조

```
app/src/main/java/com/calmride/
├── core/        순수 Kotlin 로직 (단위 테스트 대상)
│   ├── CueEngine        예고 시점 계산 (도달 시간 = 남은 거리 ÷ 차속)
│   ├── ToneSynth        100Hz 순음·예고음 PCM 생성, 좌우 패닝
│   ├── PrimingBalance   앉은 자리에 맞춘 100Hz 좌우 크기
│   ├── RearSeats        SDK 좌석 탑승 값을 AAOS 정의대로 해석
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
./gradlew :app:testDebugUnitTest   # 단위 테스트 30개
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
- **오디오 포커스:** 예고음은 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`(음악을 줄이고 함께 재생), 100Hz 사운드는 `AUDIOFOCUS_GAIN_TRANSIENT`로 요청합니다. 포커스를 잃으면 멈춥니다(100Hz는 0.4초에 걸쳐 서서히).
- **좌우 밸런스:** 차의 밸런스·페이더 설정(`setBalanceTowardRight`)은 [공개 API](https://developer.android.com/reference/android/car/media/CarAudioManager)가 아니라 시스템 앱만 바꿀 수 있습니다. 그래서 앱이 내는 소리의 좌우 크기만 조절합니다.
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
| 뒷좌석 탑승 제안 | 처음 검증 때는 통과로 봤지만, 11-05 재검증에서 아무도 없을 때도 카드가 뜨는 것을 발견했습니다(아래 SDK 좌석 값 문제). 고친 뒤에는 부팅 직후 "뒷좌석 빔"이고, 뒷좌석을 탑승으로 바꾸면 카드가 뜹니다 |
| 환기 | 멀미 모드에서 풍량 3→5, 내기순환 꺼짐(외기 유입) 확인 |
| 1분 사운드 | 차량 오디오 서비스가 `USAGE_MEDIA`·`GAIN_TRANSIENT` 포커스를 허락했고 카운트다운이 동작함 |
| 주행 중 화면 | 기어 D에서 시스템이 앱 화면을 "주행 중에는 사용할 수 없습니다"로 막고, 포그라운드 서비스는 계속 동작함 |
| 감속·차선 알림 (실제 신호) | Virtual Console로 기어 D에서 가속하다 브레이크·방향지시등을 조작하자 알림음이 `GAIN_TRANSIENT_MAY_DUCK`으로 허락받아 재생됨. 주행 후 화면에 "최근 알림음: 감속 알림 (브레이크 작동)" 표시 |
| 경로 예고음 (실제 경로 안내) | 에뮬레이터 내비게이션 앱으로 경로 안내를 시작하자 앱이 NaviHelper `GetTBTInfo`로 회전·도착 정보를 받고, 도달 시간이 예고 시점 안으로 들어오자 예고음 재생. GPS가 고정돼 차속만 올려 시험했으므로 실제 접근 상황은 아님 |
| 알림 상한 | 1분에 6번을 채우면 다음 알림이 막히고, 60초가 지나자 다시 재생 |
| 자원 사용 (주행 중) | 메모리 PSS 58.8MB, CPU 기기 전체의 0.5~1.4% (`-accel off` 환경이라 실제보다 높게 나옴). 기준은 CPU 5% 미만, RAM 400MB 이하 |
| 데모 주행 예고음 | 데모 주행에서 예고음 4번이 모두 `GAIN_TRANSIENT_MAY_DUCK`으로 포커스를 허락받아 재생됨 (예: "좌회전 9m 앞") |
| 운전자 맞춤 | 주행 종료(D→P) 후 컨디션 질문, 코칭 표시, 예고 시점 1.5→1.7초 조정, 프로필 저장 확인 |

### 100Hz 사운드 추가 검증 (2026-11-05)

| 항목 | 결과 |
| --- | --- |
| 차가 움직이면 끄기 | P에서 켠 1분 사운드가 D로 바꾼 뒤 0km/h로 서 있는 7초 동안 계속 재생되고, 가속하자 약 1.6초 안에 끝남(0.4초 서서히 줄이기 포함, 에뮬레이터 오디오가 실제보다 느림) |
| 신호 대기 재생 (택시) | P→D 직후 10초 정차 중에는 재생하지 않음 → 달리다 멈추자 약 4초 뒤 자동 재생 → 출발하자 끝남 → 다시 멈추자 처음부터 1분 전체 재생 → 다 들은 뒤에는 다시 틀지 않음. 주행 중에는 화면이 막혀 오디오 포커스 로그로 확인 |
| 좌우 보정 (수동) | '자리 바꾸기'로 "뒷좌석 왼쪽 · 오른쪽 스피커 +2dB" → "가운데" → "뒷좌석 오른쪽 · 왼쪽 스피커 +2dB" 순서로 바뀜 |
| 좌우 보정 (자동) | Virtual Console에서 뒷좌석 왼쪽만 탑승이면 "뒷좌석 왼쪽 · 오른쪽 스피커 +2dB (자동)", 오른쪽만이면 "왼쪽 스피커 +2dB", 양쪽 다 탑승이면 "가운데", 비우면 "뒷좌석 빔" |

검증 중에 찾아서 고친 버그도 있습니다.
- **차량 서비스 재시작 시 앱 종료:** `Car.createCar()`를 생명주기 리스너 없이 호출하면, 차량 서비스가 재시작될 때 Android가 앱을 강제 종료했습니다. → 리스너 방식으로 바꿔 재연결하도록 했습니다.
- **내기순환 설정 실패:** 풍량용 구역 ID(0x5)로 내기순환을 설정해서 실패했습니다. → 기능마다 자기 구역을 조회하도록 바꿨습니다.
- **코칭 문구 모순:** 컨디션이 낮은데도 "부드럽게 운전하셨어요… 예고음을 줄일게요"가 나왔습니다. → 문구와 예고음 설정을 바로잡았습니다.
- **경로 안내 거리 계산:** NaviHelper TBT 목록에서 두 번째 항목부터는 앞 안내 지점에서 잰 거리인데(예: 좌회전 82m, 좌회전 208m, 도착 58m), 모두 차에서부터의 거리로 읽었습니다. 그래서 도착 예고음이 회전보다 먼저 울렸습니다. → 거리를 누적하도록 고쳤습니다. 수정 후 에뮬레이터 재확인은 아직 하지 않았습니다.
- **Pleos SDK 좌석 탑승 값이 반대 (11-05 재검증에서 발견):** Vehicle SDK 2.3.2는 원래 값이 1일 때 `true`(탑승)를 주는데, AAOS 정의로 1은 빈자리(`VACANT`), 2가 탑승(`OCCUPIED`)입니다([AOSP `VehicleSeatOccupancyState`](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/automotive/vehicle/aidl_property/android/hardware/automotive/vehicle/VehicleSeatOccupancyState.aidl)). 에뮬레이터 Virtual Console도 빈자리에 1, 탑승에 2를 보냅니다. 그래서 아무도 없는데 탑승 제안이 떴습니다. → AAOS 정의대로 읽되, 한 번이라도 빈자리를 보고한 좌석만 믿도록 고쳤습니다(센서가 없어 `UNKNOWN`인 좌석 대비). 실차에서 확인하고 Pleos에 문의할 예정입니다.
- **앱 시작 전부터 앉아 있던 승객을 못 알아봄 (11-05 재검증에서 발견):** SDK 리스너가 첫 값을 주지 않은 좌석이 있었고, 빈자리를 한 번 본 좌석만 믿게 해 둬서 처음부터 앉아 있던 승객을 놓쳤습니다. → 시작할 때 현재 값을 직접 묻고, 뒷좌석 중 하나라도 빈자리를 보고하면 센서가 동작하는 것으로 보도록 고쳤습니다.
- **내려도 탑승 제안 카드가 남음 (11-05 재검증에서 발견):** → 뒷좌석이 비면 카드를 닫도록 고쳤습니다.
- **P→D 순간 100Hz가 꺼짐 (11-05 재검증에서 발견):** P에서는 정차 타이머를 시작하지 않아, D로 바꾼 직후 2초 동안 '움직이는 중'으로 판단했습니다. → P를 이미 멈춰 있던 상태로 보도록 고쳤습니다.
- **택시 재생이 출발 직후 시작 (11-05 재검증에서 발견):** P→D 직후 서 있는 동안에도 신호 대기로 보고 재생했습니다. → 이번 주행에서 한 번이라도 달린 뒤의 정차에서만 재생하도록 고쳤습니다.
- **출발해도 100Hz가 꺼지지 않음 (코드 점검 중 발견):** 기어 D에서 정차 중에 1분 사운드를 켜면, 출발한 뒤에도 남은 시간 동안 계속 울렸습니다. 주행 시작(P→D) 때만 소리를 껐기 때문입니다. → 차가 움직이면 0.4초에 걸쳐 서서히 끄도록 고쳤습니다.

스크린샷은 [`docs/screenshots/`](docs/screenshots/)에 있습니다. `06`은 실제 브레이크 신호로 울린 감속 알림, `07`은 실제 경로 안내를 받는 화면입니다. `07`의 "도착 50m"는 위 거리 계산 버그가 고쳐지기 전의 화면입니다. `08`은 뒷좌석 왼쪽 탑승을 자동으로 알아본 좌우 보정, `10`은 신호 대기에서 저절로 다시 시작된 1분 사운드입니다.

### 아직 확인하지 못한 것

- [ ] GPS가 실제로 움직일 때 회전 직전 예고 시점이 맞는지, `onCautionPointInfo`(과속방지턱)가 들어오는지. 에뮬레이터는 GPS가 고정되고 시험 경로에 방지턱이 없었습니다.
- [ ] 경로 안내 거리 수정 후 에뮬레이터 재확인
- [ ] 차량 스피커에서 좌우 패닝이 들리는지 (실차)
- [ ] 좌석별 좌우 보정이 실제로 양쪽 귀의 크기를 맞추는지 (실차에서 소음계로 측정. 에뮬레이터는 소리 크기를 잴 수 없음)
- [ ] 실차의 좌석 탑승 값이 에뮬레이터처럼 AAOS 정의(1 빈자리, 2 탑승)를 따르는지 (Pleos에 문의 예정)
- [ ] 뒷좌석 안전벨트 신호 (Virtual Console에 항목이 없어 확인 못 함)
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
| [06_자체_검증_계획.md](docs/06_자체_검증_계획.md) | 설문 문항, 실차 파일럿 절차, 1분 사운드 크기 측정, 파일럿용 예고음·100Hz WAV([`pilot_sounds/`](docs/pilot_sounds/)) |

## 근거 연구

- Gu et al. (2025), *Just 1-min exposure to a pure tone at 100 Hz … may improve motion sickness*, Environmental Health and Preventive Medicine — [나고야대 보도자료](https://en.nagoya-u.ac.jp/news/articles/research_information_267/)
- Kuiper et al. (2020), *Knowing what's coming: Anticipatory audio cues can mitigate motion sickness*, Applied Ergonomics 85 — [논문 정보](https://research.vu.nl/en/publications/knowing-whats-coming-anticipatory-audio-cues-can-mitigate-motion-/)

Calm Ride는 멀미를 '치료'하는 앱이 아니라 '완화를 돕는' 앱입니다.
