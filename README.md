# ScreenJoy — Android Client

폴더블 기기에서 PC 게임 스트리밍을 할 때 쓰는 투명 오버레이 가상 게임패드입니다.
터치 입력을 UDP 바이트 패킷으로 PC에 보내면, [ScreenJoyServer](https://github.com/liamParkDev/ScreenJoyServer)가 이를 받아 가상 Xbox 360 컨트롤러 입력으로 변환합니다.

*[English](#english) below.*

---

## 왜 만들었나

갤럭시 Z 폴드처럼 화면비가 비표준인 기기에서 문라이트(Moonlight) 등으로 PC 게임을 스트리밍하면, 기존 가상 패드 앱들은 두 가지 문제가 있었습니다.

- **레이아웃이 화면에 안 맞음** — 접을 때와 펼칠 때 화면 크기가 통째로 바뀌는데 컨트롤이 따라오지 못함
- **인풋렉** — 상당수 매핑 앱이 TCP 기반이거나 ADB 연결, 광고 시청 등 번거로운 절차를 요구

ScreenJoy는 컨트롤마다 독립된 시스템 오버레이 창을 띄우고, 상태를 20바이트 UDP 패킷으로 직접 쏘는 방식으로 이 두 가지를 해결합니다.

## 동작 방식

```
[Android]  오버레이 터치 감지
              ↓  20바이트 UDP (포트 50001)
[Windows]  ScreenJoyServer 수신 · 파싱
              ↓
           ViGEmBus 드라이버 → OS가 물리 Xbox 360 패드로 인식
           keybd_event      → 커스텀 키는 키보드 입력으로 주입
```

컨트롤은 각각 별도의 `TYPE_APPLICATION_OVERLAY` 창입니다. 덕분에 **컨트롤이 없는 빈 공간의 터치는 창 자체가 없어서 아래 앱(문라이트 등)으로 그대로 통과**하고, 컨트롤 간 동시 입력도 OS 레벨에서 자연스럽게 처리됩니다.

## 기능

- 좌/우 아날로그 스틱, D-Pad, ABXY, 숄더(LB/RB), 트리거(LT/RT)
- **커스텀 키보드 키** — 알파벳/숫자/F1~F12/Tab/Space/Enter 등을 버튼으로 추가해 PC에 키 입력 전송
- **레이아웃 편집** — 드래그로 이동, 핀치로 크기 조절. 개별 편집과 세트 편집(D-Pad·ABXY 묶음) 지원
- **컨트롤 숨김** — 안 쓰는 버튼을 숨기면 화면에서 사라지고 그 자리 터치는 아래 앱으로 통과
- **프로필** — 게임마다 다른 배치를 이름 붙여 저장하고 불러오기
- **폴드 대응** — 접기/펼치기로 화면 크기가 바뀌어도 컨트롤이 가장자리 기준으로 배치를 유지
- 패드 전체를 잠깐 치우는 토글 버튼

## 설치 및 사용

**필요 조건**

- Android 8.0 (API 26) 이상 — 오버레이 창 API 요구사항
- 같은 네트워크에 연결된 Windows PC + [ScreenJoyServer](https://github.com/liamParkDev/ScreenJoyServer) 실행
- PC에 [ViGEmBus](https://github.com/nefarius/ViGEmBus) 드라이버 설치

**순서**

1. PC에서 ScreenJoyServer를 실행합니다.
2. 앱을 열고 화면의 **PC IP**를 탭해 서버 PC의 주소를 입력합니다.
3. **오버레이 권한 요청**으로 "다른 앱 위에 표시" 권한을 허용합니다.
4. **오버레이 시작**을 누르면 컨트롤이 화면에 뜹니다. 이제 문라이트 등 다른 앱 위에서도 유지됩니다.
5. 배치를 바꾸려면 **편집 모드**로 들어가 드래그·핀치로 조정하고 완료를 누릅니다.

> **삼성 기기 참고** — 포그라운드 서비스여도 배터리 관리에 의해 종료될 수 있습니다. 설정 → 배터리에서 이 앱을 "제한 없음"으로 두면 게임 중에 오버레이가 사라지지 않습니다.

## 통신 프로토콜

UDP, 포트 `50001`, 페이로드 20바이트, little-endian. 매 전송마다 전체 상태를 담기 때문에 패킷이 유실돼도 다음 패킷에서 복구됩니다.

| 오프셋 | 내용 |
|---|---|
| 0 | 버튼 비트마스크 — `0x01` A, `0x02` B, `0x04` X, `0x08` Y, `0x10` Up, `0x20` Down, `0x40` Left, `0x80` Right |
| 1–8 | LeftThumbX / LeftThumbY / RightThumbX / RightThumbY (각 Int16) |
| 9 | 숄더 비트마스크 — `0x01` LB, `0x02` RB |
| 10–11 | LT / RT (0–255) |
| 12–19 | 커스텀 키 Windows VK 코드, 최대 8개 동시 입력 |

## 기술 스택

Kotlin · WindowManager(`TYPE_APPLICATION_OVERLAY`) · 포그라운드 서비스 · Coroutines · DataStore
minSdk 26 / targetSdk 37 / AGP 9.3.2

## 빌드

```bash
./gradlew assembleDebug
```

---

<a name="english"></a>

# English

A transparent overlay gamepad for PC game streaming on foldable Android devices. Touch input is sent to a Windows PC as UDP byte packets, where [ScreenJoyServer](https://github.com/liamParkDev/ScreenJoyServer) turns it into a virtual Xbox 360 controller.

## Why

On non-standard aspect ratios like the Galaxy Z Fold, existing virtual gamepad apps break in two ways: layouts don't adapt when folding changes the screen size entirely, and many add input latency or require ADB setup and ads. ScreenJoy gives each control its own system overlay window and sends state as a 20-byte UDP packet.

## How it works

Each control is a separate `TYPE_APPLICATION_OVERLAY` window, so **touches on empty space pass straight through to the app underneath** (Moonlight, etc.), and multi-touch across controls is handled by the OS. The Windows daemon feeds parsed values to the ViGEmBus kernel driver, so games see a real Xbox 360 pad. Custom keyboard keys are injected via `keybd_event`.

## Features

- Dual analog sticks, D-Pad, ABXY, shoulders (LB/RB), triggers (LT/RT)
- Custom keyboard keys (letters, digits, F1–F12, Tab, Space, Enter, …)
- Layout editing — drag to move, pinch to resize; individual or grouped (D-Pad / ABXY)
- Hide unused controls — hidden controls disappear and let touches pass through
- Profiles — save and restore named layouts per game
- Fold-aware — controls keep their placement relative to screen edges when the screen size changes

## Requirements

- Android 8.0 (API 26) or newer
- Windows PC on the same network running [ScreenJoyServer](https://github.com/liamParkDev/ScreenJoyServer)
- [ViGEmBus](https://github.com/nefarius/ViGEmBus) driver installed on the PC

## Setup

1. Start ScreenJoyServer on your PC.
2. Open the app and tap **PC IP** to enter your PC's address.
3. Grant the "display over other apps" permission.
4. Tap **오버레이 시작** (Start overlay).
5. Use **편집 모드** (Edit mode) to arrange controls.

## Protocol

UDP port `50001`, 20-byte little-endian payload carrying the full controller state on every send, so a dropped packet is corrected by the next one. See the table in the Korean section above for the byte layout.

## Build

```bash
./gradlew assembleDebug
```
