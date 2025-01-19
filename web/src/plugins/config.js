import body_0 from "../assets/imgs/themes/body_0.png";
import content_0 from "../assets/imgs/themes/content_0.png";
import popup_0 from "../assets/imgs/themes/popup_0.png";
import body_1 from "../assets/imgs/themes/body_1.png";
import content_1 from "../assets/imgs/themes/content_1.png";
import popup_1 from "../assets/imgs/themes/popup_1.png";
import body_2 from "../assets/imgs/themes/body_2.png";
import content_2 from "../assets/imgs/themes/content_2.png";
import popup_2 from "../assets/imgs/themes/popup_2.png";
import body_3 from "../assets/imgs/themes/body_3.png";
import content_3 from "../assets/imgs/themes/content_3.png";
import popup_3 from "../assets/imgs/themes/popup_3.png";
import body_5 from "../assets/imgs/themes/body_5.png";
import content_5 from "../assets/imgs/themes/content_5.png";
import popup_5 from "../assets/imgs/themes/popup_5.png";
import body_6 from "../assets/imgs/themes/body_6.png";
import content_6 from "../assets/imgs/themes/content_6.png";
// import popup_6 from "../assets/imgs/themes/popup_6.png";

const defaultDayConfig = {
  configDefaultType: "白天默认",
  name: "内置白天",
  theme: 0,
  font: 0,
  chineseFont: "简体",
  fontSize: 18,
  fontWeight: 400,
  fontColor: "#262626",
  bodyColor: "#eadfca",
  contentColor: "#fff",
  popupColor: "#ede7da",
  themeType: "day",
  readMethod: "上下滑动",
  clickMethod: "自动",
  animateMSTime: 300, // 翻页动画时长
  readWidth: 800,
  lineHeight: 1.8, // 行高
  paragraphSpace: 0.2, // 段间距
  autoReadingMethod: "像素滚动",
  autoReadingPixel: 1,
  autoReadingLineTime: 1000,
  pageMode: "自适应",
  selectionAction: "操作弹窗"
};
const defaultNightConfig = {
  configDefaultType: "黑夜默认",
  name: "内置黑夜",
  theme: 6,
  font: 0,
  chineseFont: "简体",
  fontSize: 18,
  fontWeight: 400,
  fontColor: "#666666",
  bodyColor: "#121212",
  contentColor: "#171717",
  popupColor: "#121212",
  themeType: "night",
  readMethod: "上下滑动",
  clickMethod: "自动",
  animateMSTime: 300, // 翻页动画时长
  readWidth: 800,
  lineHeight: 1.8, // 行高
  paragraphSpace: 0.2, // 段间距
  autoReadingMethod: "像素滚动",
  autoReadingPixel: 1,
  autoReadingLineTime: 1000,
  pageMode: "自适应",
  selectionAction: "操作弹窗"
};
const settings = {
  shelfConfig: {
    showBookGroup: -1
  },
  searchConfig: {
    searchType: "multi",
    bookSourceGroup: "",
    bookSourceUrl: "",
    concurrentCount: 24
  },
  customConfigList: [defaultDayConfig, defaultNightConfig],
  config: {
    ...defaultDayConfig,
    customConfig: "内置白天",
    autoTheme: true, // 自动切换主题
    pageType: "正常"
  },
  speechVoiceConfig: {
    voiceName: "",
    speechRate: 1,
    speechPitch: 1
  },
  defaultNightTheme: 6,
  themes: [
    {
      body: "url(" + body_0 + ") repeat",
      content: "url(" + content_0 + ") repeat",
      popup: "url(" + popup_0 + ") repeat"
    },
    {
      body: "url(" + body_1 + ") repeat",
      content: "url(" + content_1 + ") repeat",
      popup: "url(" + popup_1 + ") repeat"
    },
    {
      body: "url(" + body_2 + ") repeat",
      content: "url(" + content_2 + ") repeat",
      popup: "url(" + popup_2 + ") repeat"
    },
    {
      body: "url(" + body_3 + ") repeat",
      content: "url(" + content_3 + ") repeat",
      popup: "url(" + popup_3 + ") repeat"
    },
    {
      body: "#ebcece repeat",
      content: "#f5e4e4 repeat",
      popup: "#faeceb repeat"
    },
    {
      body: "url(" + body_5 + ") repeat",
      content: "url(" + content_5 + ") repeat",
      popup: "url(" + popup_5 + ") repeat"
    },
    {
      body: "url(" + body_6 + ") repeat",
      content: "url(" + content_6 + ") repeat",
      popup: "#121212"
    },
    {
      body: "#f7f7f7 repeat",
      content: "#fff repeat",
      popup: "#f7f7f7 repeat"
    }
  ],
  fonts: [
    {
      fontFamily: "custom-system"
    },
    // 黑体
    {
      // fontFamily:
      //   '-apple-system, "Noto Sans", "Helvetica Neue", Helvetica, "Nimbus Sans L", Arial, "Liberation Sans", "PingFang SC", "Hiragino Sans GB", "Noto Sans CJK SC", "Source Han Sans SC", "Source Han Sans CN", "Microsoft YaHei", "Wenquanyi Micro Hei", "WenQuanYi Zen Hei", "ST Heiti", SimHei, "WenQuanYi Zen Hei Sharp", sans-serif'
      fontFamily: "custom-ht, reader-ht"
    },
    // 楷体
    {
      // fontFamily:
      // 'Baskerville, Georgia, "Liberation Serif", "Kaiti SC", STKaiti, "AR PL UKai CN", "AR PL UKai HK", "AR PL UKai TW", "AR PL UKai TW MBE", "AR PL KaitiM GB", KaiTi, KaiTi_GB2312, DFKai-SB, "TW-Kai", serif',
      fontFamily: "custom-kt, reader-kt"
      // fontFamily: "STKaiti",
      // "-fx-font-family": "STKaiti"
    },
    // 宋体
    {
      // fontFamily:
      // 'Georgia, "Nimbus Roman No9 L", "Songti SC", "Noto Serif CJK SC", "Source Han Serif SC", "Source Han Serif CN", STSong, "AR PL New Sung", "AR PL SungtiL GB", NSimSun, SimSun, "TW-Sung", "WenQuanYi Bitmap Song", "AR PL UMing CN", "AR PL UMing HK", "AR PL UMing TW", "AR PL UMing TW MBE", PMingLiU, MingLiU, serif',
      fontFamily: "custom-st, reader-st"
      // fontFamily: "'Source Han Serif CN'",
      // "-fx-font-family": "'Source Han Serif CN'"
    },
    // 仿宋
    {
      // fontFamily:
      //   'Baskerville, "Times New Roman", "Liberation Serif", STFangsong, FangSong, FangSong_GB2312, "CWTEX-F", serif',
      fontFamily: "custom-fs, reader-fs"
      // fontFamily: "STFangsong",
      // "-fx-font-family": "STFangsong"
    }
  ]
};
export const errorTypeList = [
  "UnknownHostException",
  "ConnectException: Failed to connect",
  "SocketException: Connection reset",
  "SSLHandshakeException",
  "responseCode: 307",
  "responseCode: 400",
  "responseCode: 403",
  "responseCode: 404",
  "responseCode: 500",
  "responseCode: 502",
  "responseCode: 503",
  "responseCode: 504",
  "responseCode: 513"
];
export const defaultReplaceRule = {
  name: "",
  pattern: "",
  replacement: "",
  scope: "",
  isRegex: false,
  isEnabled: true
};
export const defaultBookmark = {
  bookName: "",
  bookAuthor: "",
  chapterIndex: 0,
  chapterPos: 0,
  chapterName: "",
  bookText: "",
  content: ""
};
export const syncConfigFiled = Object.keys(defaultDayConfig).concat([
  "contentBGImg"
]);
export const customFonts = [
  "custom-system",
  "custom-ht",
  "custom-kt",
  "custom-st",
  "custom-fs"
];
export default settings;
