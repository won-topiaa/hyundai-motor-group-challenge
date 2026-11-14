# Pleos Connect SDK 조사

> **NoiseTrace 브랜치 메모:** 이전 주제(Calm Ride) 때 조사한 문서입니다. 기능별 판단은 Calm Ride 기준이지만, SDK 목록·권한·주행 중 화면·오디오 포커스·CPU/RAM 기준·에뮬레이터 설정은 NoiseTrace에도 그대로 적용됩니다. NoiseTrace에서 새로 쓰는 API(와이퍼, 에어컨, 창문, 주행거리, 바깥 기온, 제한속도, 현재 주소, 마이크)는 `02_기획/NoiseTrace_기획안.md` 6절에 정리했습니다.

Calm Ride에 필요한 API를 Pleos Playground 개발 문서에서 확인한 결과입니다. (확인일: 2026-11-03)

API 이름은 문서 사이트의 페이지 목록(sitemap)에서 확인했습니다. 상세 페이지 본문은 제대로 불러와지지 않아 파라미터·반환값은 아직 확인하지 못했습니다.

> **추가 조사 반영 (`05_구현_가능성_조사.md`):** LLM SDK는 제공이 종료됐습니다. 차속은 AAOS `PERF_VEHICLE_SPEED`로 읽습니다. 다음 회전까지 남은 거리는 `TBTInfo.distance`로, 주의 지점까지 남은 거리는 `CautionPoint.remainDistance`로 받습니다. 아래 확인 필요 항목 중 다수가 해결됐습니다.

## 개발 환경

- Android API 28 이상, Gradle 8.0 이상, Java 또는 Kotlin
- Pleos Connect 전용 AVD 필요 (일반 에뮬레이터 이미지로는 Vehicle Service가 없음)
- Vehicle SDK Sample App 기준 버전: Vehicle SDK 2.3.2, Vehicle Service 2.3.22, AVD `Pleos_Connect_v2.1`(Android 14), JDK 17, AGP 8.8.2, Kotlin 2.0.0, compileSdk/targetSdk 35
- 권한은 Manifest에 선언합니다 (실행 중 승인 창 방식이 아님). 실제로 쓰는 API의 최소 권한만 선언하세요.
- 사용이 끝난 Listener는 등록할 때와 같은 객체·area로 해제하고, 마지막에 `Vehicle.release()`를 호출합니다.

## 확인한 API

| SDK | API·데이터 | Calm Ride 용도 |
| --- | --- | --- |
| NaviHelper | 회전 종류 `TurnType` | 회전 예고음 |
| NaviHelper | 주의 지점 `CautionPointType` (`SPEED_BUMP`, `DOWNHILL_CAUTION_AREA` 등) | 방지턱·내리막 예고음 |
| NaviHelper | 교통 혼잡 `TrafficCongestionType` | 정체 구간 감속 예고 |
| NaviHelper | `NaviMode.SIMULATION_GUIDING` | 에뮬레이터 모의주행 데모 |
| Vehicle | `getCurrentBrakePedalStatus`, `registerBrakePedalStatus` | 제동 시작 감지 |
| Vehicle | `registerTurnSignalState` | 차선 변경 예고 |
| Vehicle | `registerSteeringWheelAngle` | 회전 중 상태, 습관 학습 |
| Vehicle | `setHvacFanSpeed`, 외기·내기 전환 상태 리스너 | 멀미 모드 환기 |
| Vehicle | `EvRegenerativeBrakingState` (DISABLED / PARTIALLY_ENABLED / FULLY_ENABLED / UNKNOWN) | 회생제동 단계 추천 |
| Vehicle | 시트 위치 (앞뒤·높이) 리스너 | 운전자 구분 |
| Vehicle | `registerDrivingModeState`, 크루즈 컨트롤 상태 | 참고용 |
| FusedLocation | `registerFusedLocationCallback` (위도·경도·고도·방향·시각) | 속도·가감속 추정, 정차 판단 |
| TextToSpeech / SpeechToText | 음성 출력 / 입력 | 모드 안내, 음성 명령 |
| LLM | `generateContent`, `startChat`, 모드 `ON_DEVICE` / `SERVER` | 맞춤 코칭 문장 (확장) |

## 확인하지 못했거나 추가 확인이 필요한 부분

- [ ] 공개 Vehicle SDK에서 **차속·가속도 API를 찾지 못함** → 위치 변화로 추정하는 방식을 기본으로 함
- [ ] 회생제동 상태를 **읽는 API**와 권한 (상수만 확인됨)
- [ ] NaviHelper가 다음 회전·주의 지점까지 **남은 거리**를 주는지
- [ ] 외기·내기 전환을 앱에서 **바꿀 수 있는지**(쓰기 권한)
- [ ] 차량 스피커에서 앱 오디오의 **좌우 방향 출력**이 되는지
- [ ] 시트 위치 API의 **읽기 권한**
- [ ] LLM SDK를 **이번 공모전에서 써도 되는지** (공모전 안내의 SDK 목록에는 없음, 주최 측 문의)

## 문서 링크

- [Pleos Connect 개발 환경 설정 (에뮬레이터 포함)](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [Pleos Connect SDK 공식 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/)
- [Vehicle SDK Sample App 사용 가이드](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app)
- [Vehicle SDK 연동 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup)
- [Vehicle SDK 권한 선언 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)
- [Vehicle SDK Callback 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/callback)
- [NaviHelper SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)
- [App Review Guidelines](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide)
- [Pleos Playground](https://pleos.ai/playground)
