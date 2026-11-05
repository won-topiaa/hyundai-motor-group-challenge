// Creates the Calm Ride survey form and the separate pilot sign-up form. Usage: docs/06_자체_검증_계획.md, section 1.

const SCALE_LOW = '전혀 아니다';
const SCALE_HIGH = '매우 그렇다';

function createForms() {
  const pilot = createPilotForm_();
  const survey = createSurveyForm_(pilot.getPublishedUrl());
  Logger.log('설문 응답 링크 (배포용): ' + survey.getPublishedUrl());
  Logger.log('설문 편집 링크: ' + survey.getEditUrl());
  Logger.log('파일럿 신청 응답 링크: ' + pilot.getPublishedUrl());
  Logger.log('파일럿 신청 편집 링크: ' + pilot.getEditUrl());
}

function createSurveyForm_(pilotUrl) {
  const form = FormApp.create('전기차 동승 멀미 설문 (약 4분)');
  form.setDescription([
    '안녕하세요. 현대자동차그룹 차량용 앱 개발 공모전에 참가한 대학생 팀입니다. 자동차, 특히 전기차를 탈 때 동승자가 겪는 멀미에 대해 여쭙니다.',
    '',
    '- 약 4분 걸리고, 이름·연락처 등 개인을 알아볼 수 있는 정보는 받지 않습니다.',
    '- 응답은 공모전 기획서와 발표 자료에 통계로만 쓰고, 공모전 일정이 끝나는 2027년 2월에 삭제합니다.',
    '- 만 19세 이상만 응답해 주세요. 언제든 응답을 멈출 수 있습니다.',
  ].join('\n'));
  form.setCollectEmail(false);
  form.setProgressBar(true);
  form.setConfirmationMessage([
    '응답해 주셔서 감사합니다.',
    '',
    '11월 16~30일에 실제 차를 타고 진행하는 짧은 시험(15분 탑승 2번)에 함께할 성인을 찾고 있습니다. 관심이 있으면 아래 링크에서 따로 신청해 주세요. 이 설문 응답과는 연결되지 않습니다.',
    pilotUrl,
  ].join('\n'));

  // Section 1: consent and eligibility
  form.addCheckboxItem()
    .setTitle('위 내용을 확인했고 설문에 참여합니다.')
    .setChoiceValues(['확인했습니다'])
    .setRequired(true);
  const qRider = form.addMultipleChoiceItem()
    .setTitle('최근 1년 동안 운전하지 않고 승객으로 차를 탄 적이 있나요?')
    .setRequired(true);

  const pBasic = form.addPageBreakItem().setTitle('기본 정보');
  choice_(form, '나이대를 골라 주세요.', ['19~24세', '25~29세', '30대', '40대', '50대', '60대 이상'], true);
  choice_(form, '성별을 골라 주세요.', ['여성', '남성', '응답하지 않음'], false);
  choice_(form, '승객으로 탈 때 주로 어느 자리에 앉나요?', ['조수석', '뒷좌석', '비슷하게 섞여 있음'], true);

  form.addPageBreakItem().setTitle('멀미 경험');
  choice_(form, '최근 1년 동안 승객으로 차를 타고 멀미한 적이 얼마나 있나요?', ['없음', '1~2번', '가끔', '자주', '거의 매번'], true);
  checkbox_(form, '차에서 멀미가 가장 심해지는 상황을 모두 골라 주세요.', [
    '회전이 많을 때',
    '자주 서고 출발할 때 (정체, 신호)',
    '과속방지턱이나 울퉁불퉁한 길',
    '휴대폰이나 책을 볼 때',
    '공기가 답답할 때',
    '뒷좌석에 앉을 때',
    '멀미한 적이 없음',
  ]);
  checkbox_(form, '멀미를 줄이려고 지금 쓰는 방법을 모두 골라 주세요.', [
    '멀미약 (먹는 약, 붙이는 패치)',
    '창밖 보기',
    '창문 열기, 환기',
    '잠자기',
    '앞자리에 타기',
    '운전자에게 천천히 가 달라고 하기',
    '따로 하는 것 없음',
  ]);
  const qEv = form.addMultipleChoiceItem()
    .setTitle('전기차(전기 택시 포함)에 승객으로 타 본 적이 있나요?')
    .setRequired(true);

  const pEv = form.addPageBreakItem().setTitle('전기차에서의 멀미');
  choice_(form, '전기차를 탔을 때 멀미는 휘발유·디젤차와 비교해 어땠나요?',
    ['훨씬 덜함', '조금 덜함', '비슷함', '조금 더 심함', '훨씬 더 심함', '비교하기 어려움'], true);
  checkbox_(form, '전기차에서 불편했던 순간을 모두 골라 주세요.', [
    '운전자가 가속 페달에서 발을 뗄 때 울컥하며 서는 느낌',
    '출발할 때 갑자기 튀어 나가는 느낌',
    '엔진 소리가 없어 언제 서고 출발할지 알기 어려움',
    '회전할 때',
    '불편했던 적 없음',
  ]);

  const pFeature = form.addPageBreakItem()
    .setTitle('새 기능에 대한 생각')
    .setHelpText('저희가 만드는 앱은 차의 내비게이션 정보를 써서, 차가 회전하거나 감속하기 1~2초 전에 그쪽 스피커에서 짧고 부드러운 알림음을 냅니다. 출발 전에는 차가 서 있는 동안 1분 동안 낮은 소리(100Hz, 일상 대화 정도 크기)를 들려줍니다.');
  scale_(form, '승객으로 탈 때 이 기능을 써 보고 싶나요?');
  scale_(form, '출발 전에 1분 동안 소리를 듣고 출발한다면 기다릴 수 있나요?');
  choice_(form, '주행 중 짧은 알림음은 1분에 몇 번까지 괜찮을까요?',
    ['1~2번', '3~4번', '5~6번', '더 많아도 괜찮음', '알림음 자체가 싫음'], true);
  const qTaxi = form.addMultipleChoiceItem()
    .setTitle('최근 1년 동안 택시를 얼마나 탔나요?')
    .setRequired(true);

  const pTaxi = form.addPageBreakItem().setTitle('택시');
  choice_(form, '택시에서 멀미 때문에 불편했던 적이 있나요?', ['없음', '가끔', '자주'], true);
  choice_(form, '전기 택시가 배차됐을 때 멀미가 걱정돼 취소하거나 다른 차를 기다린 적이 있나요?',
    ['있음', '없지만 그러고 싶었던 적은 있음', '없음', '전기 택시가 배차된 적 없음'], true);
  scale_(form, '택시에 이 기능이 있다면 기사에게 켜 달라고 하겠나요?');

  const pKids = form.addPageBreakItem().setTitle('아이와 함께 탈 때');
  const qKids = form.addMultipleChoiceItem()
    .setTitle('아이(만 13세 미만)와 함께 차를 타나요? 아이가 멀미를 하나요?')
    .setRequired(true);

  const pKidsUse = form.addPageBreakItem().setTitle('아이를 위한 사용');
  scale_(form, '아이를 위해 이 기능을 쓰겠나요?');

  const pEnd = form.addPageBreakItem().setTitle('마무리');
  form.addParagraphTextItem()
    .setTitle('차에서 멀미 때문에 가장 힘들었던 경험을 한두 문장으로 적어 주세요. (선택)')
    .setRequired(false);

  // Branches need the section items, so the choices are set after every section exists.
  qRider.setChoices([
    qRider.createChoice('예', pBasic),
    qRider.createChoice('아니요', FormApp.PageNavigationType.SUBMIT),
  ]);
  qEv.setChoices([
    qEv.createChoice('예', pEv),
    qEv.createChoice('아니요', pFeature),
    qEv.createChoice('잘 모르겠음', pFeature),
  ]);
  qTaxi.setChoices([
    qTaxi.createChoice('타지 않음', pKids),
    qTaxi.createChoice('1년에 몇 번', pTaxi),
    qTaxi.createChoice('한 달에 1~3번', pTaxi),
    qTaxi.createChoice('일주일에 1번 이상', pTaxi),
  ]);
  qKids.setChoices([
    qKids.createChoice('아이와 타지 않음', pEnd),
    qKids.createChoice('타지만 멀미하지 않음', pEnd),
    qKids.createChoice('가끔 멀미함', pKidsUse),
    qKids.createChoice('자주 멀미함', pKidsUse),
  ]);
  return form;
}

function createPilotForm_() {
  const form = FormApp.create('Calm Ride 실차 파일럿 참여 신청');
  form.setDescription([
    '11월 16~30일에 실제 차를 타고 진행하는 짧은 시험에 함께할 성인을 찾습니다.',
    '',
    '- 같은 15분 시내 경로를 다른 날 두 번 탑승합니다. 한 번은 예고음이 나오고, 한 번은 나오지 않습니다.',
    '- 뒷좌석에서 정해진 글을 휴대폰으로 읽으며, 1분마다 멀미 정도(0~10점)를 말로 알려 주시면 됩니다.',
    '- 멀미 점수가 6점(가벼운 메스꺼움) 이상이면 바로 차를 세우고 중단합니다.',
    '- 만 19세 이상만 참여할 수 있습니다.',
  ].join('\n'));
  form.setCollectEmail(false);
  form.setConfirmationMessage('신청해 주셔서 감사합니다. 일정이 정해지면 적어 주신 연락처로 연락드리겠습니다.');

  form.addCheckboxItem()
    .setTitle('개인정보 수집·이용 동의')
    .setHelpText([
      '수집 목적: 파일럿 일정 안내와 연락',
      '수집 항목: 이름(또는 부를 이름), 연락처',
      '보유 기간: 파일럿이 끝나면 지체 없이 삭제 (늦어도 2026년 12월 31일)',
      '동의를 거부할 수 있으며, 거부하면 파일럿에 참여할 수 없습니다.',
    ].join('\n'))
    .setChoiceValues(['동의합니다'])
    .setRequired(true);
  form.addCheckboxItem()
    .setTitle('만 19세 이상인가요?')
    .setChoiceValues(['만 19세 이상입니다'])
    .setRequired(true);
  form.addTextItem().setTitle('이름 (또는 부를 이름)').setRequired(true);
  form.addTextItem().setTitle('연락처 (휴대폰 번호 또는 카카오톡 ID)').setRequired(true);
  form.addCheckboxItem()
    .setTitle('참여할 수 있는 시간대를 모두 골라 주세요. (11/16~11/30)')
    .setChoiceValues(['평일 낮', '평일 저녁', '주말 낮', '주말 저녁'])
    .setRequired(true);
  choice_(form, '평소 차에서 멀미를 얼마나 하나요?', ['거의 하지 않음', '가끔 함', '자주 함'], true);
  choice_(form, '차에서 멀미로 토한 적이 있나요?', ['없음', '어릴 때 있음', '최근 1년 안에 있음'], true);
  return form;
}

function choice_(form, title, values, required) {
  return form.addMultipleChoiceItem().setTitle(title).setChoiceValues(values).setRequired(required);
}

function checkbox_(form, title, values) {
  return form.addCheckboxItem().setTitle(title).setChoiceValues(values).showOtherOption(true).setRequired(true);
}

function scale_(form, title) {
  return form.addScaleItem().setTitle(title).setBounds(1, 5).setLabels(SCALE_LOW, SCALE_HIGH).setRequired(true);
}
