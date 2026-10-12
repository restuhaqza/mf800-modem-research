// mifi-agent — agen "coding assistant" mungil yang benar-benar bisa jalan DI DALAM
// modem MF800 (Android 4.4.4, armv7, libc bionic).
//
// Kenapa bikin sendiri, bukan pakai OpenCode? OpenCode didistribusikan sebagai
// binary Bun untuk x64/arm64 saja (dan butuh glibc); modem ini armv7 + bionic dan
// tak punya node/bash/python. Lihat docs/mini-agent.md untuk percobaan lengkapnya.
//
// Keunikan pendekatan ini: seluruh binary memakai *hanya* stdlib Go, lalu
// di-build static (CGO_ENABLED=0, GOOS=linux GOARCH=arm GOARM=7) — sama seperti
// trik speedtest-go. Tanpa glibc, tanpa linker Android.
//
// Pemakaian di terminal web modem:
//
//	mifi-agent "cek sinyal modem dan jelaskan"      # satu kali jalan
//	mifi-agent                                       # mode ngobrol (REPL)
//	mifi-agent --config                              # lihat konfigurasi
//	mifi-agent --selftest                            # uji tool tanpa API
//
// Provider default: DeepSeek (endpoint OpenAI-compatible di https://api.deepseek.com).
// Bisa diganti ke provider OpenAI-compatible lain lewat presets (-p openai) atau
// base URL manual. Preset: deepseek (default), openai.
//
// Konfigurasi (env menang atas file $HOME/.mifi-agent.json):
//
//	MIFI_AI_PROVIDER   deepseek (default) | openai
//	MIFI_AI_BASE_URL   override base URL (mis. https://api.deepseek.com)
//	MIFI_AI_KEY        API key (wajib untuk mode chat)
//	MIFI_AI_MODEL      default deepseek-flash (preset deepseek)
//	MIFI_AI_SYSTEM     override system prompt
package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const version = "0.1.0"

// defaultSystem sengaja memuat fakta device supaya model tidak menebak-nebak
// (toolbox 4.4 tanpa uname/head/tail, busybox tersedia, dsb).
const defaultSystem = `Kamu "mifi-agent", asisten ngoprek yang berjalan LANGSUNG DI DALAM sebuah modem WiFi 4G murah (MF800).

Fakta device (percayai ini, jangan mengarang):
- Android 4.4.4 KitKat, Qualcomm MSM8916, CPU armv7 (32-bit), RAM ~390 MB, libc bionic (bukan glibc).
- Toolbox Android 4.4 sangat minim: sering tak ada uname, head, tail, awk, sed, tr. BusyBox ada di /system/bin/busybox; panggil appletnya lewat "busybox <cmd>" bila perlu.
- Tak ada node, bash, atau python. Shell: /system/bin/sh (mksh).
- Kamu berjalan sebagai uid aplikasi biasa (bukan root). /data bisa ditulis.
- Ada kanal AT ke baseband: /system/bin/modem_at "AT+CSQ" dsb.
- Ada helper API di http://127.0.0.1:8080 (endpoint /api/* butuh header X-Auth).

Cara kerja: pakai tool untuk MEMERIKSA fakta sebelum menyimpulkan; jangan mengarang keluaran perintah. Jawab ringkas dalam Bahasa Indonesia, teks polos (hindari tabel markdown lebar karena terminal sempit).`

// ---------------------------------------------------------------------------
// konfigurasi
// ---------------------------------------------------------------------------

type Config struct {
	Provider string `json:"provider,omitempty"`
	BaseURL  string `json:"base_url"`
	APIKey   string `json:"api_key"`
	Model    string `json:"model"`
	System   string `json:"system"`
	Timeout  int    `json:"timeout_seconds,omitempty"`
}

// providers: preset base URL + model default per penyedia (semua OpenAI-compatible).
var providers = map[string]struct{ BaseURL, Model string }{
	"deepseek": {"https://api.deepseek.com", "deepseek-flash"},
	"openai":   {"https://api.openai.com/v1", "gpt-4o-mini"},
}

const defaultProvider = "deepseek"

func loadConfig() Config {
	prov := defaultProvider
	if v := os.Getenv("MIFI_AI_PROVIDER"); v != "" {
		prov = v
	}
	p, ok := providers[prov]
	if !ok {
		prov, p = defaultProvider, providers[defaultProvider]
	}
	cfg := Config{
		Provider: prov,
		BaseURL:  p.BaseURL,
		Model:    p.Model,
		System:   defaultSystem,
		Timeout:  120,
	}
	// Berkas konfigurasi dicari di beberapa tempat (menang yang paling akhir).
	// Penting: $HOME sering KOSONG di jalur /api/sh (non-PTY), jadi kita juga
	// melihat relatif ke binary — konvensi helper: <filesDir>/bin/../home.
	for _, path := range configPaths() {
		b, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		var f Config
		if json.Unmarshal(b, &f) != nil {
			continue
		}
		loadedConfigs = append(loadedConfigs, path)
		if f.Provider != "" {
			cfg.Provider = f.Provider
		}
		if f.BaseURL != "" {
			cfg.BaseURL = f.BaseURL
		}
		if f.APIKey != "" {
			cfg.APIKey = f.APIKey
		}
		if f.Model != "" {
			cfg.Model = f.Model
		}
		if f.System != "" {
			cfg.System = f.System
		}
		if f.Timeout > 0 {
			cfg.Timeout = f.Timeout
		}
	}
	// env menang atas file
	if v := os.Getenv("MIFI_AI_BASE_URL"); v != "" {
		cfg.BaseURL = v
	}
	if v := os.Getenv("MIFI_AI_KEY"); v != "" {
		cfg.APIKey = v
	}
	if v := os.Getenv("MIFI_AI_MODEL"); v != "" {
		cfg.Model = v
	}
	if v := os.Getenv("MIFI_AI_SYSTEM"); v != "" {
		cfg.System = v
	}
	cfg.BaseURL = strings.TrimRight(cfg.BaseURL, "/")
	return cfg
}

// loadedConfigs mencatat berkas konfigurasi yang benar-benar terbaca (untuk --config).
var loadedConfigs []string

// configPaths mengembalikan kandidat lokasi konfigurasi dari prioritas rendah ke tinggi.
func configPaths() []string {
	var paths []string
	seen := map[string]bool{}
	add := func(p string) {
		p = filepath.Clean(p)
		if !seen[p] {
			seen[p] = true
			paths = append(paths, p)
		}
	}
	add(".mifi-agent.json")
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		add(filepath.Join(dir, ".mifi-agent.json"))
		// konvensi helper: engine di <filesDir>/bin, home di <filesDir>/home
		add(filepath.Join(dir, "..", "home", ".mifi-agent.json"))
	}
	if home, err := os.UserHomeDir(); err == nil {
		add(filepath.Join(home, ".mifi-agent.json"))
	}
	return paths
}

// ---------------------------------------------------------------------------
// tipe API (OpenAI-compatible /chat/completions)
// ---------------------------------------------------------------------------

type Message struct {
	Role             string     `json:"role"`
	Content          string     `json:"content,omitempty"`
	ReasoningContent string     `json:"reasoning_content,omitempty"`
	ToolCalls        []ToolCall `json:"tool_calls,omitempty"`
	ToolCallID       string     `json:"tool_call_id,omitempty"`
}

type ToolCall struct {
	ID       string `json:"id"`
	Type     string `json:"type"`
	Function struct {
		Name      string `json:"name"`
		Arguments string `json:"arguments"`
	} `json:"function"`
}

type ToolSpec struct {
	Type     string `json:"type"`
	Function struct {
		Name        string         `json:"name"`
		Description string         `json:"description"`
		Parameters  map[string]any `json:"parameters"`
	} `json:"function"`
}

type chatRequest struct {
	Model      string    `json:"model"`
	Messages   []Message `json:"messages"`
	Tools      []ToolSpec `json:"tools,omitempty"`
	ToolChoice string    `json:"tool_choice,omitempty"`
}

type chatResponse struct {
	Choices []struct {
		Message Message `json:"message"`
	} `json:"choices"`
	Error *struct {
		Message string `json:"message"`
	} `json:"error,omitempty"`
}

// ---------------------------------------------------------------------------
// definisi tool
// ---------------------------------------------------------------------------

func toolSpecs() []ToolSpec {
	obj := func(props map[string]any, required ...string) map[string]any {
		return map[string]any{"type": "object", "properties": props, "required": required}
	}
	str := func(desc string) map[string]any {
		return map[string]any{"type": "string", "description": desc}
	}

	var run, read, write, ls ToolSpec

	run.Type = "function"
	run.Function.Name = "run_command"
	run.Function.Description = "Jalankan perintah shell di modem ini lalu kembalikan stdout+stderr + exit code. Ingat: toolbox 4.4 minim (pakai 'busybox <cmd>' bila applet tak ada)."
	run.Function.Parameters = obj(map[string]any{
		"command": str("perintah yang dijalankan lewat /system/bin/sh -c"),
		"cwd":     str("direktori kerja (opsional)"),
	}, "command")

	read.Type = "function"
	read.Function.Name = "read_file"
	read.Function.Description = "Baca isi sebuah file teks (maks 16 KB)."
	read.Function.Parameters = obj(map[string]any{"path": str("path absolut file")}, "path")

	write.Type = "function"
	write.Function.Name = "write_file"
	write.Function.Description = "Tulis/overwrite sebuah file teks (orang tua direktori dibuat otomatis)."
	write.Function.Parameters = obj(map[string]any{
		"path":    str("path absolut file"),
		"content": str("isi file"),
	}, "path", "content")

	ls.Type = "function"
	ls.Function.Name = "list_dir"
	ls.Function.Description = "Daftar isi direktori."
	ls.Function.Parameters = obj(map[string]any{"path": str("path direktori")}, "path")

	return []ToolSpec{run, read, write, ls}
}

func shellPath() string {
	if _, err := os.Stat("/system/bin/sh"); err == nil {
		return "/system/bin/sh"
	}
	return "sh"
}

// executeTool menjalankan satu tool dan mengembalikan hasil sebagai teks.
func executeTool(name, argsJSON string) string {
	switch name {
	case "run_command":
		var a struct {
			Command string `json:"command"`
			Cwd     string `json:"cwd"`
		}
		if err := json.Unmarshal([]byte(argsJSON), &a); err != nil {
			return "error: argumen tidak valid: " + err.Error()
		}
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()
		cmd := exec.CommandContext(ctx, shellPath(), "-c", a.Command)
		if a.Cwd != "" {
			cmd.Dir = a.Cwd
		}
		var buf bytes.Buffer
		cmd.Stdout = &buf
		cmd.Stderr = &buf
		err := cmd.Run()
		rc := 0
		if err != nil {
			if ee, ok := err.(*exec.ExitError); ok {
				rc = ee.ExitCode()
			} else {
				return "error menjalankan: " + err.Error()
			}
		}
		out := truncate(buf.String(), 8000)
		if out == "" {
			out = "(tanpa keluaran)"
		}
		return fmt.Sprintf("exit=%d\n%s", rc, out)

	case "read_file":
		var a struct {
			Path string `json:"path"`
		}
		if err := json.Unmarshal([]byte(argsJSON), &a); err != nil {
			return "error: " + err.Error()
		}
		b, err := os.ReadFile(a.Path)
		if err != nil {
			return "error: " + err.Error()
		}
		return truncate(string(b), 16000)

	case "write_file":
		var a struct {
			Path    string `json:"path"`
			Content string `json:"content"`
		}
		if err := json.Unmarshal([]byte(argsJSON), &a); err != nil {
			return "error: " + err.Error()
		}
		if dir := filepath.Dir(a.Path); dir != "" && dir != "." {
			_ = os.MkdirAll(dir, 0o755)
		}
		if err := os.WriteFile(a.Path, []byte(a.Content), 0o644); err != nil {
			return "error: " + err.Error()
		}
		return fmt.Sprintf("ok: %d byte ditulis ke %s", len(a.Content), a.Path)

	case "list_dir":
		var a struct {
			Path string `json:"path"`
		}
		if err := json.Unmarshal([]byte(argsJSON), &a); err != nil {
			return "error: " + err.Error()
		}
		ents, err := os.ReadDir(a.Path)
		if err != nil {
			return "error: " + err.Error()
		}
		var lines []string
		for _, e := range ents {
			kind := "-"
			if e.IsDir() {
				kind = "d"
			}
			size := int64(-1)
			if info, err := e.Info(); err == nil {
				size = info.Size()
			}
			if e.IsDir() {
				lines = append(lines, fmt.Sprintf("%s  %-8s %s", kind, "-", e.Name()))
			} else {
				lines = append(lines, fmt.Sprintf("%s  %-8d %s", kind, size, e.Name()))
			}
		}
		sort.Strings(lines)
		if len(lines) == 0 {
			return "(direktori kosong)"
		}
		return strings.Join(lines, "\n")

	default:
		return "error: tool tidak dikenal: " + name
	}
}

func truncate(s string, max int) string {
	if len(s) <= max {
		return strings.TrimRight(s, "\n")
	}
	return strings.TrimRight(s[:max], "\n") + fmt.Sprintf("\n… (dipotong, total %d byte)", len(s))
}

// ---------------------------------------------------------------------------
// agent
// ---------------------------------------------------------------------------

type Agent struct {
	cfg    Config
	client *http.Client
	hist   []Message // percakapan (termasuk system)
	out    io.Writer
	labels bool // tampilkan langkah tool
}

func NewAgent(cfg Config, out io.Writer) *Agent {
	return &Agent{
		cfg:    cfg,
		client: &http.Client{Timeout: time.Duration(cfg.Timeout) * time.Second},
		hist:   []Message{{Role: "system", Content: cfg.System}},
		out:    out,
		labels: true,
	}
}

func (a *Agent) Reset() {
	a.hist = []Message{{Role: "system", Content: a.cfg.System}}
}

// complete mengirim seluruh riwayat dan mengembalikan pesan assistant.
func (a *Agent) complete() (Message, error) {
	body, _ := json.Marshal(chatRequest{
		Model:      a.cfg.Model,
		Messages:   a.hist,
		Tools:      toolSpecs(),
		ToolChoice: "auto",
	})
	req, err := http.NewRequest("POST", a.cfg.BaseURL+"/chat/completions", bytes.NewReader(body))
	if err != nil {
		return Message{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+a.cfg.APIKey)
	resp, err := a.client.Do(req)
	if err != nil {
		return Message{}, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var cr chatResponse
	if err := json.Unmarshal(raw, &cr); err != nil {
		return Message{}, fmt.Errorf("respons tak terurai (HTTP %d): %s", resp.StatusCode, truncate(string(raw), 300))
	}
	if cr.Error != nil {
		return Message{}, fmt.Errorf("API error: %s", cr.Error.Message)
	}
	if resp.StatusCode != 200 {
		return Message{}, fmt.Errorf("HTTP %d: %s", resp.StatusCode, truncate(string(raw), 300))
	}
	if len(cr.Choices) == 0 {
		return Message{}, fmt.Errorf("API tak mengembalikan pilihan")
	}
	return cr.Choices[0].Message, nil
}

// Ask menjalankan satu giliran pengguna: kirim → eksekusi tool → ulangi sampai
// model berhenti memanggil tool, lalu cetak jawaban akhir.
func (a *Agent) Ask(user string) error {
	a.hist = append(a.hist, Message{Role: "user", Content: user})

	for round := 0; round < 12; round++ {
		msg, err := a.complete()
		if err != nil {
			return err
		}
		// thinking mode DeepSeek: tampilkan jejak penalaran (redup), tapi JANGAN
		// dikirim balik ke API pada giliran berikutnya (tak diterima sebagai input).
		if rc := strings.TrimSpace(msg.ReasoningContent); rc != "" && a.labels {
			fmt.Fprintf(a.out, "\n\033[90m%s\033[0m\n", indent(truncate(rc, 900), "  "))
		}
		msg.ReasoningContent = ""
		a.hist = append(a.hist, msg)

		if len(msg.ToolCalls) == 0 {
			if txt := strings.TrimSpace(msg.Content); txt != "" {
				fmt.Fprintf(a.out, "\n%s\n", txt)
			}
			return nil
		}
		if txt := strings.TrimSpace(msg.Content); txt != "" {
			fmt.Fprintf(a.out, "\n%s\n", txt)
		}
		for _, tc := range msg.ToolCalls {
			if a.labels {
				fmt.Fprintf(a.out, "\n\033[35m▸ %s\033[0m %s", tc.Function.Name, oneLine(tc.Function.Arguments))
			}
			result := executeTool(tc.Function.Name, tc.Function.Arguments)
			if a.labels {
				fmt.Fprintf(a.out, "\n\033[90m%s\033[0m\n", indent(truncate(result, 1200), "  "))
			}
			a.hist = append(a.hist, Message{
				Role:       "tool",
				ToolCallID: tc.ID,
				Content:    result,
			})
		}
	}
	fmt.Fprintln(a.out, "\n(berhenti: terlalu banyak langkah tool)")
	return nil
}

func oneLine(s string) string {
	s = strings.ReplaceAll(s, "\n", " ")
	return truncate(s, 140)
}

func indent(s, prefix string) string {
	lines := strings.Split(s, "\n")
	for i := range lines {
		lines[i] = prefix + lines[i]
	}
	return strings.Join(lines, "\n")
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

const usage = `mifi-agent — agen AI mungil untuk modem MF800 (Android 4.4.4, armv7)

Pemakaian:
  mifi-agent "pertanyaan"     satu kali jalan
  mifi-agent                  mode ngobrol (REPL)
  mifi-agent -p openai ...    ganti provider (deepseek | openai)
  mifi-agent -m <model> ...   override model
  mifi-agent --config         tampilkan konfigurasi aktif
  mifi-agent --selftest       uji lapisan tool tanpa API
  mifi-agent --version

Di dalam REPL:  /exit  /reset  /config  /help

Provider default: deepseek (https://api.deepseek.com, model deepseek-flash).
Konfigurasi lewat env atau $HOME/.mifi-agent.json:
  MIFI_AI_PROVIDER (deepseek | openai)
  MIFI_AI_BASE_URL (override base URL)
  MIFI_AI_KEY      (wajib untuk chat)
  MIFI_AI_MODEL    (default deepseek-flash)`

func main() {
	cfg := loadConfig()

	var prompt []string
	args := os.Args[1:]
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "-m", "--model":
			if i+1 < len(args) {
				i++
				cfg.Model = args[i]
			}
		case "-p", "--provider":
			if i+1 < len(args) {
				i++
				if p, ok := providers[args[i]]; ok {
					cfg.Provider = args[i]
					// preset hanya menimpa base URL bila env tak mensetnya eksplisit
					if os.Getenv("MIFI_AI_BASE_URL") == "" {
						cfg.BaseURL = p.BaseURL
					}
					cfg.Model = p.Model
				} else {
					fmt.Fprintf(os.Stderr, "provider tak dikenal: %s (pilihan: deepseek, openai)\n", args[i])
					os.Exit(2)
				}
			}
		case "-v", "--version":
			fmt.Printf("mifi-agent %s\n", version)
			return
		case "-h", "--help":
			fmt.Println(usage)
			return
		case "--config":
			printConfig(cfg)
			return
		case "--selftest":
			selftest()
			return
		default:
			prompt = append(prompt, args[i])
		}
	}

	if len(prompt) > 0 {
		if err := requireKey(cfg); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		a := NewAgent(cfg, os.Stdout)
		if err := a.Ask(strings.Join(prompt, " ")); err != nil {
			fmt.Fprintln(os.Stderr, "error:", err)
			os.Exit(1)
		}
		return
	}

	repl(cfg)
}

func requireKey(cfg Config) error {
	if strings.TrimSpace(cfg.APIKey) == "" {
		return fmt.Errorf("API key belum diset.\nSet salah satu:\n  export MIFI_AI_KEY=...\natau tulis %s lalu isi {\"api_key\":\"...\"}", "~/.mifi-agent.json")
	}
	return nil
}

func printConfig(cfg Config) {
	key := "(kosong)"
	if cfg.APIKey != "" {
		k := cfg.APIKey
		if len(k) > 8 {
			k = k[:4] + "…" + k[len(k)-4:]
		}
		key = k
	}
	fmt.Printf("provider : %s\n", cfg.Provider)
	fmt.Printf("base_url : %s\n", cfg.BaseURL)
	fmt.Printf("model    : %s\n", cfg.Model)
	fmt.Printf("api_key  : %s\n", key)
	fmt.Printf("timeout  : %ds\n", cfg.Timeout)
	if len(loadedConfigs) > 0 {
		fmt.Printf("config   : %s\n", strings.Join(loadedConfigs, ", "))
	} else {
		fmt.Printf("config   : (tidak ada berkas; pakai env saja)\n")
	}
}

func repl(cfg Config) {
	a := NewAgent(cfg, os.Stdout)
	fmt.Printf("mifi-agent %s — model '%s'\n", version, cfg.Model)
	if err := requireKey(cfg); err != nil {
		fmt.Fprintln(os.Stderr, "\n⚠ "+err.Error())
	} else {
		fmt.Printf("target '%s' — ketik /help untuk bantuan, /exit keluar\n", cfg.BaseURL)
	}

	sc := bufio.NewScanner(os.Stdin)
	sc.Buffer(make([]byte, 0, 1<<20), 1<<20)
	for {
		fmt.Print("\n\033[36m›\033[0m ")
		if !sc.Scan() {
			fmt.Println()
			return
		}
		line := strings.TrimSpace(sc.Text())
		switch line {
		case "":
			continue
		case "/exit", "/quit":
			return
		case "/help":
			fmt.Println("perintah: /exit  /reset (lupakan percakapan)  /config  /help")
			continue
		case "/reset":
			a.Reset()
			fmt.Println("(percakapan direset)")
			continue
		case "/config":
			printConfig(cfg)
			continue
		}
		if err := a.Ask(line); err != nil {
			fmt.Fprintln(os.Stderr, "error:", err)
		}
	}
}

// selftest memverifikasi lapisan tool tanpa memanggil API (berguna di device).
func selftest() {
	fmt.Printf("mifi-agent %s selftest\n", version)
	fmt.Printf("shell  : %s\n", shellPath())
	steps := []struct{ name, args string }{
		{"run_command", `{"command":"echo halo dari mifi-agent"}`},
		{"run_command", `{"command":"id"}`},
		{"list_dir", `{"path":"/data/local/tmp"}`},
		{"write_file", `{"path":"/data/local/tmp/mifi-agent-test.txt","content":"ok\n"}`},
		{"read_file", `{"path":"/data/local/tmp/mifi-agent-test.txt"}`},
		{"run_command", `{"command":"rm -f /data/local/tmp/mifi-agent-test.txt"}`},
		{"run_command", `{"command":"/system/bin/busybox uname -m 2>/dev/null || echo no-busybox"}`},
	}
	for _, s := range steps {
		fmt.Printf("\n▸ %s %s\n", s.name, s.args)
		fmt.Println(indent(truncate(executeTool(s.name, s.args), 1000), "  "))
	}
	fmt.Println("\nselftest selesai.")
}
