# -*- coding: utf-8 -*-
import flet as ft
import requests
import time
import threading
import queue
import edge_tts
import asyncio
import os
import json
import base64

CONFIG_FILE = 'config.json'

def load_config():
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, 'r', encoding='utf-8') as f: return json.load(f)
        except: pass
    return { "stock_code": "600519", "speak_interval": 15, "speak_price": True, "speak_pct": True, "speak_current_hand": True, "speak_amount": True, "speak_vol_ratio": True, "speak_speed": False, "speak_large_orders": True }

def save_config(cfg):
    try:
        with open(CONFIG_FILE, 'w', encoding='utf-8') as f: json.dump(cfg, f, ensure_ascii=False, indent=2)
    except: pass

REFRESH_INTERVAL = 2  
LARGE_ORDER_THRESHOLD = 500  

stock_history = {}    
last_speak_time = 0
speech_queue = queue.Queue()
app_config = load_config()

def get_market_prefix(code):
    if code.startswith("6"): return "sh" + code
    elif code.startswith("0") or code.startswith("3"): return "sz" + code
    elif code.startswith("8") or code.startswith("4"): return "bj" + code
    return "sh" + code

def query_tencent_stocks(code):
    if not code: return ""
    try: return requests.get(f"https://qt.gtimg.cn/q={get_market_prefix(code)}", timeout=3).text
    except: return ""

def parse_qq_data(raw_data):
    lines = raw_data.strip().split(";")
    for line in lines:
        if not line or "=" not in line: continue
        body = line.split("=")[1].strip('"')
        arr = body.split("~")
        if len(arr) < 50: continue
            
        name, code, price = arr[1], arr[2], float(arr[3])
        change_pct = float(arr[32])
        total_vol = int(arr[6])
        amount_wan = float(arr[37]) 
        vol_ratio = float(arr[49]) 
        
        bids = [(float(arr[i]), int(arr[i+1])) for i in range(9, 18, 2)]
        asks = [(float(arr[i]), int(arr[i+1])) for i in range(19, 28, 2)]
        
        bid_names = ["买一", "买二", "买三", "买四", "买五"]
        ask_names = ["卖一", "卖二", "卖三", "卖四", "卖五"]
        
        large_bids_disp, large_asks_disp = [], []
        large_bids_spk, large_asks_spk = [], []
        
        for j, (p, v) in enumerate(bids):
            if v >= LARGE_ORDER_THRESHOLD and p > 0:
                large_bids_disp.append(f"{bid_names[j]}排{v}手")
                large_bids_spk.append(f"{bid_names[j]}{v}手托单")
                
        for j, (p, v) in enumerate(asks):
            if v >= LARGE_ORDER_THRESHOLD and p > 0:
                large_asks_disp.append(f"{ask_names[j]}排{v}手")
                large_asks_spk.append(f"{ask_names[j]}{v}手压单")
        
        current_hand, speed = 0, 0.0
        if code in stock_history: 
            current_hand = max(0, total_vol - stock_history[code]["total_vol"])
            speed = round(change_pct - stock_history[code]["change_pct"], 2)
            
        stock_history[code] = { "total_vol": total_vol, "change_pct": change_pct }
        amt_str = f"{round(amount_wan/10000, 2)}亿" if amount_wan > 10000 else f"{round(amount_wan, 2)}万"
        
        return { "name": name, "price": price, "change_pct": change_pct, "current_hand": current_hand, "large_bids_disp": large_bids_disp, "large_asks_disp": large_asks_disp, "large_bids_spk": large_bids_spk, "large_asks_spk": large_asks_spk, "amount": amt_str, "vol_ratio": vol_ratio, "speed": speed }
    return None

def main(page: ft.Page):
    page.title = "摸鱼听盘啦。。"
    page.window_width = 450
    page.window_height = 850
    page.scroll = "adaptive"
    page.theme_mode = "light"
    page.theme = ft.Theme(font_family="Microsoft YaHei")
    
    # 彻底弃用 Pygame, 采用 Flet 官方 Native Audio 以支持 Android APK！
    audio_player = ft.Audio(autoplay=True)
    page.overlay.append(audio_player)
    
    async def generate_and_play(text):
        try:
            communicate = edge_tts.Communicate(text, "zh-CN-XiaoxiaoNeural", rate="+10%")
            temp_file = "temp_speech.mp3"
            await communicate.save(temp_file)
            
            with open(temp_file, "rb") as f:
                b64_audio = base64.b64encode(f.read()).decode("utf-8")
                
            audio_player.src_base64 = b64_audio
            page.update()
            
            if os.path.exists(temp_file): os.remove(temp_file)
        except Exception as e:
            pass

    def _speak_loop():
        while True:
            text = speech_queue.get()
            if text is None: break
            try: asyncio.run(generate_and_play(text))
            except: pass
            time.sleep(3) # 预留点播放时间防顶号
            speech_queue.task_done()
            
    threading.Thread(target=_speak_loop, daemon=True).start()
    
    is_running = [False]

    def push_to_speak(text):
        global last_speak_time
        interval = int(interval_input.value or 15)
        if time.time() - last_speak_time > interval:
            last_speak_time = time.time()
            speech_queue.put(text)
            status_text.value = f"📢 上次播报: {time.strftime('%H:%M:%S')}"
            page.update()

    def update_config():
        app_config["stock_code"] = stock_input.value
        app_config["speak_interval"] = int(interval_input.value or 15)
        app_config["speak_price"] = cb_price.value
        app_config["speak_pct"] = cb_pct.value
        app_config["speak_current_hand"] = cb_hand.value
        app_config["speak_large_orders"] = cb_large.value
        app_config["speak_amount"] = cb_amount.value
        app_config["speak_vol_ratio"] = cb_vol.value
        app_config["speak_speed"] = cb_speed.value
        save_config(app_config)

    def update_loop():
        code = stock_input.value.strip()
        while is_running[0]:
            raw = query_tencent_stocks(code)
            if raw:
                item = parse_qq_data(raw)
                if item:
                    st = "涨" if item['change_pct'] > 0 else "跌" if item['change_pct'] < 0 else "平"
                    price_color = "red" if item['change_pct'] > 0 else "green" if item['change_pct'] < 0 else "black"
                    
                    span_title = ft.TextSpan(f"【{item['name']}】", ft.TextStyle(size=18, weight="bold"))
                    span_price = ft.TextSpan(f" 现价: {item['price']} ({st}{abs(item['change_pct'])}%) ", ft.TextStyle(color=price_color, size=18, weight="bold"))
                    span_speed = ft.TextSpan(f" 涨速: {item['speed']}%", ft.TextStyle(size=16, weight="normal"))
                    span_sub = ft.TextSpan(f"\n成交额: {item['amount']} | 量比: {item['vol_ratio']} | 现手: {item['current_hand']}\n", ft.TextStyle(size=16, weight="normal", color="blue_grey"))
                    spans = [span_title, span_price, span_speed, span_sub]
                    
                    if item['large_asks_disp']: spans.append(ft.TextSpan("\n🔻压单: " + " , ".join(item['large_asks_disp']), ft.TextStyle(color="green", size=15, weight="w500")))
                    if item['large_bids_disp']: spans.append(ft.TextSpan("\n🔺托单: " + " , ".join(item['large_bids_disp']), ft.TextStyle(color="red", size=15, weight="w500")))

                    info_text.spans = spans
                    
                    spk_parts = [f"{item['name']}"]
                    if cb_price.value: spk_parts.append(f"{item['price']}元")
                    if cb_pct.value: spk_parts.append(f"{st}{abs(item['change_pct'])}%")
                    if cb_speed.value and abs(item['speed']) > 0: spk_parts.append(f"涨速{abs(item['speed'])}%")
                    if cb_amount.value: spk_parts.append(f"成交额{item['amount']}")
                    if cb_vol.value: spk_parts.append(f"量比{item['vol_ratio']}")
                    if cb_hand.value: spk_parts.append(f"现手{item['current_hand']}")
                    spk_text = "，".join(spk_parts) + "。"
                    
                    if cb_large.value:
                        if item['large_asks_spk']: spk_text += f"。注意：{'，'.join(item['large_asks_spk'])}。"
                        if item['large_bids_spk']: spk_text += f"。注意：{'，'.join(item['large_bids_spk'])}。"
                    
                    page.update()
                    if spk_text != "。": push_to_speak(spk_text)
            time.sleep(REFRESH_INTERVAL)

    def toggle_run(e):
        if not is_running[0]:
            if not stock_input.value.strip(): return
            update_config()
            stock_history.clear()
            
            is_running[0] = True
            btn.text, btn.icon, btn.color = "停止盯盘", "stop", "red"
            controls_group.disabled = True
            status_text.value = f"🟢 正在监控: {app_config['stock_code']}..."
            page.update()
            threading.Thread(target=update_loop, daemon=True).start()
        else:
            is_running[0] = False
            btn.text, btn.icon, btn.color = "开始自动盯盘", "play_arrow", "blue"
            controls_group.disabled = False
            status_text.value = "🔴 监控已停止"
            page.update()

    stock_input = ft.TextField(label="股票代码", value=app_config.get("stock_code", ""), width=150)
    interval_input = ft.TextField(label="播报间隔(秒)", value=str(app_config.get("speak_interval", 15)), width=150)
    
    cb_price = ft.Checkbox(label="现价", value=app_config.get("speak_price", True))
    cb_pct = ft.Checkbox(label="涨幅", value=app_config.get("speak_pct", True))
    cb_speed = ft.Checkbox(label="涨速", value=app_config.get("speak_speed", False))
    cb_amount = ft.Checkbox(label="成交额", value=app_config.get("speak_amount", False))
    cb_vol = ft.Checkbox(label="量比", value=app_config.get("speak_vol_ratio", False))
    cb_hand = ft.Checkbox(label="现手", value=app_config.get("speak_current_hand", True))
    cb_large = ft.Checkbox(label="大单盘口", value=app_config.get("speak_large_orders", True))
    
    controls_group = ft.Column([
        ft.Row([stock_input, interval_input]),
        ft.Text("请勾选需要播报的内容:"),
        ft.Row([ft.Column([cb_price, cb_pct, cb_speed]), ft.Column([cb_amount, cb_vol, cb_hand, cb_large])], alignment=ft.MainAxisAlignment.SPACE_AROUND)
    ])

    btn = ft.ElevatedButton("开始自动盯盘", on_click=toggle_run, icon="play_arrow")
    status_text = ft.Text("准备就绪", color="grey")
    info_text = ft.Text(spans=[])
    
    page.add(ft.Column([ft.Text("摸鱼听盘啦。。", size=24, weight="bold"), controls_group, btn, status_text, ft.Divider(), info_text]))

ft.app(target=main)