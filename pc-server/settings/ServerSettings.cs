// Ccomics PC 서버 설정: 휴대폰에서 읽을 PC 폴더를 고르고, 서버를 다시 시작하고, 연결 코드를 복사한다.
// 빌드: pc-server\settings\build.ps1 (Windows에 들어 있는 .NET Framework csc.exe 사용, 추가 설치 없음)
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Management;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

/// <summary>Windows 탐색기와 같은 모양의 폴더 선택 창(IFileOpenDialog, 폴더 모드). 경로를 직접 붙여 넣을 수도 있다.</summary>
static class FolderPicker {
  [System.Runtime.InteropServices.ComImport, System.Runtime.InteropServices.Guid("DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7")]
  class FileOpenDialogCom { }

  [System.Runtime.InteropServices.ComImport, System.Runtime.InteropServices.Guid("42f85136-db7e-439c-85f1-e4075d135fc8"),
   System.Runtime.InteropServices.InterfaceType(System.Runtime.InteropServices.ComInterfaceType.InterfaceIsIUnknown)]
  interface IFileDialog {
    [System.Runtime.InteropServices.PreserveSig] int Show(IntPtr parent);
    void SetFileTypes(uint c, IntPtr f);
    void SetFileTypeIndex(uint i);
    void GetFileTypeIndex(out uint i);
    void Advise(IntPtr e, out uint c);
    void Unadvise(uint c);
    void SetOptions(uint o);
    void GetOptions(out uint o);
    void SetDefaultFolder(IShellItem i);
    void SetFolder(IShellItem i);
    void GetFolder(out IShellItem i);
    void GetCurrentSelection(out IShellItem i);
    void SetFileName([System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.LPWStr)] string n);
    void GetFileName([System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.LPWStr)] out string n);
    void SetTitle([System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.LPWStr)] string t);
    void SetOkButtonLabel([System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.LPWStr)] string t);
    void SetFileNameLabel([System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.LPWStr)] string t);
    void GetResult(out IShellItem i);
  }

  [System.Runtime.InteropServices.ComImport, System.Runtime.InteropServices.Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE"),
   System.Runtime.InteropServices.InterfaceType(System.Runtime.InteropServices.ComInterfaceType.InterfaceIsIUnknown)]
  interface IShellItem {
    void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
    void GetParent(out IShellItem i);
    void GetDisplayName(uint sigdn, [System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.LPWStr)] out string name);
  }

  public static string Pick(IntPtr owner, string title) {
    var dlg = (IFileDialog)new FileOpenDialogCom();
    uint opts;
    dlg.GetOptions(out opts);
    dlg.SetOptions(opts | 0x20 | 0x40 | 0x800); // 폴더 고르기, 파일시스템만, 경로가 있어야 함
    dlg.SetTitle(title);
    dlg.SetOkButtonLabel("이 폴더 추가");
    if (dlg.Show(owner) != 0) return null;
    IShellItem item;
    dlg.GetResult(out item);
    string path;
    item.GetDisplayName(0x80058000, out path); // SIGDN_FILESYSPATH
    return path;
  }
}

static class Program {
  [STAThread]
  static void Main() {
    Application.EnableVisualStyles();
    Application.SetCompatibleTextRenderingDefault(false);
    try {
      Application.Run(new SettingsForm());
    } catch (Exception e) {
      MessageBox.Show(e.Message, "Ccomics PC 서버 설정", MessageBoxButtons.OK, MessageBoxIcon.Error);
    }
  }
}

sealed class SettingsForm : Form {
  const string TaskName = "Ccomics PC 서버";
  readonly string baseDir, script, configPath, rclonePath = @"C:\Programs\rclone-v1.76.0-windows-amd64\rclone.exe";
  Dictionary<string, object> config;
  readonly ListBox folders = new ListBox();
  readonly Label status = new Label();
  readonly Button save = new Button();
  bool dirty;

  public SettingsForm() {
    baseDir = AppDomain.CurrentDomain.BaseDirectory;
    script = Path.Combine(baseDir, @"pc-server\ccomics-server.ps1");
    configPath = Path.Combine(baseDir, @"pc-server\data\config.json");
    if (!File.Exists(script)) throw new Exception("이 프로그램은 Ccomics 폴더 안에서 실행해야 합니다.\n찾지 못한 파일: " + script);
    if (!File.Exists(configPath)) throw new Exception("서버 설정 파일이 없습니다: " + configPath);
    config = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(File.ReadAllText(configPath, Encoding.UTF8));

    Text = "Ccomics PC 서버 설정";
    Font = new Font("맑은 고딕", 10f);
    AutoScaleMode = AutoScaleMode.Font;
    ClientSize = new Size(640, 470);
    MinimumSize = new Size(520, 400);
    StartPosition = FormStartPosition.CenterScreen;

    var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(14), ColumnCount = 2, RowCount = 5 };
    layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
    layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
    layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
    layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
    layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
    layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
    layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
    Controls.Add(layout);

    status.AutoSize = true;
    status.Font = new Font(Font, FontStyle.Bold);
    status.Margin = new Padding(0, 0, 0, 8);
    layout.Controls.Add(status, 0, 0);
    layout.SetColumnSpan(status, 2);

    var title = new Label { Text = "휴대폰에서 읽을 PC 폴더 (하위 폴더까지 모두 읽음)", AutoSize = true, Margin = new Padding(0, 0, 0, 4) };
    layout.Controls.Add(title, 0, 1);
    layout.SetColumnSpan(title, 2);

    folders.Dock = DockStyle.Fill;
    folders.IntegralHeight = false;
    foreach (var f in (IEnumerable)config["folders"]) folders.Items.Add(f.ToString());
    layout.Controls.Add(folders, 0, 2);

    var side = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, Margin = new Padding(10, 0, 0, 0) };
    var add = MakeButton("폴더 추가…", AddFolder);
    var remove = MakeButton("선택한 폴더 빼기", RemoveFolder);
    side.Controls.Add(add);
    side.Controls.Add(remove);
    layout.Controls.Add(side, 1, 2);

    var note = new Label {
      AutoSize = true,
      MaximumSize = new Size(600, 0),
      ForeColor = Color.DimGray,
      Margin = new Padding(0, 10, 0, 10),
      Text = "폴더를 바꾸고 저장하면 서버가 다시 시작됩니다. 휴대폰 앱에서 서재를 아래로 당겨 새로고침하면 반영됩니다.\n"
           + "처음 보는 책은 표지와 페이지 수를 만들려고 권마다 약 1MB를 받습니다(수천 권이면 Wi-Fi에서 새로고침 권장)."
    };
    layout.Controls.Add(note, 0, 3);
    layout.SetColumnSpan(note, 2);

    var bottom = new FlowLayoutPanel { FlowDirection = FlowDirection.LeftToRight, AutoSize = true, Dock = DockStyle.Fill };
    save.Text = "저장하고 서버 다시 시작";
    save.AutoSize = true;
    save.Padding = new Padding(8, 4, 8, 4);
    save.Click += (s, e) => SaveAndRestart();
    bottom.Controls.Add(save);
    bottom.Controls.Add(MakeButton("휴대폰 연결 코드 복사", CopyCode));
    bottom.Controls.Add(MakeButton("서버 다시 시작", () => Restart(true)));
    layout.Controls.Add(bottom, 0, 4);
    layout.SetColumnSpan(bottom, 2);

    FormClosing += (s, e) => {
      if (!dirty) return;
      var r = MessageBox.Show("바꾼 폴더 목록을 저장할까요?", Text, MessageBoxButtons.YesNoCancel, MessageBoxIcon.Question);
      if (r == DialogResult.Cancel) e.Cancel = true;
      else if (r == DialogResult.Yes) SaveAndRestart();
    };
    Shown += (s, e) => RefreshStatus();
    UpdateSave();
  }

  Button MakeButton(string text, Action click) {
    var b = new Button { Text = text, AutoSize = true, Padding = new Padding(8, 4, 8, 4), Margin = new Padding(0, 0, 8, 8) };
    b.Click += (s, e) => click();
    return b;
  }

  int Port { get { return Convert.ToInt32(config["port"]); } }

  bool ServerUp() {
    try {
      using (var c = new TcpClient()) {
        var r = c.BeginConnect("127.0.0.1", Port, null, null);
        return r.AsyncWaitHandle.WaitOne(800) && c.Connected;
      }
    } catch { return false; }
  }

  void RefreshStatus() {
    bool up = ServerUp();
    status.Text = up ? "● 서버 실행 중  ·  PC에 로그인하면 자동으로 켜집니다" : "● 서버 꺼짐  ·  [서버 다시 시작]을 누르세요";
    status.ForeColor = up ? Color.SeaGreen : Color.Firebrick;
  }

  void UpdateSave() {
    save.Enabled = dirty;
  }

  void AddFolder() {
    {
      string path = FolderPicker.Pick(Handle, "휴대폰에서 읽을 만화 폴더 고르기");
      if (path == null) return;
      path = path.TrimEnd('\\');
      foreach (var item in folders.Items) {
        string have = item.ToString().TrimEnd('\\');
        if (string.Equals(have, path, StringComparison.OrdinalIgnoreCase)) { MessageBox.Show("이미 들어 있는 폴더입니다.", Text); return; }
        if (path.StartsWith(have + "\\", StringComparison.OrdinalIgnoreCase)) { MessageBox.Show("이미 들어 있는 \"" + have + "\" 안의 폴더라 따로 넣지 않아도 됩니다.", Text); return; }
      }
      if (!HasComics(path)
          && MessageBox.Show("이 폴더(하위 폴더 포함)에 ZIP·CBZ 만화 파일이 없습니다.\n" + path + "\n\n그래도 추가할까요?", Text,
                 MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes)
        return;
      folders.Items.Add(path);
      dirty = true;
      UpdateSave();
    }
  }

  /** 만화 파일이 하나라도 있는지(찾는 즉시 멈춤). */
  static bool HasComics(string path) {
    try {
      foreach (var f in Directory.EnumerateFiles(path, "*.*", SearchOption.AllDirectories)) {
        string ext = Path.GetExtension(f).ToLowerInvariant();
        if (ext == ".zip" || ext == ".cbz") return true;
      }
    } catch { return true; }
    return false;
  }

  void RemoveFolder() {
    if (folders.SelectedIndex < 0) { MessageBox.Show("뺄 폴더를 목록에서 먼저 고르세요.", Text); return; }
    folders.Items.RemoveAt(folders.SelectedIndex);
    dirty = true;
    UpdateSave();
  }

  void SaveAndRestart() {
    var list = new List<string>();
    foreach (var item in folders.Items) list.Add(item.ToString());
    config["folders"] = list;
    string json = new JavaScriptSerializer().Serialize(config);
    string tmp = configPath + ".tmp";
    File.WriteAllText(tmp, json, new UTF8Encoding(false));
    File.Copy(tmp, configPath, true);
    File.Delete(tmp);
    dirty = false;
    UpdateSave();
    Restart(false);
  }

  void Restart(bool ask) {
    Cursor = Cursors.WaitCursor;
    try {
      Run("schtasks.exe", "/End /TN \"" + TaskName + "\"");
      using (var q = new ManagementObjectSearcher("SELECT ProcessId, CommandLine, ExecutablePath FROM Win32_Process WHERE Name='powershell.exe' OR Name='rclone.exe'")) {
        foreach (ManagementObject p in q.Get()) {
          string cmd = Convert.ToString(p["CommandLine"]) ?? "", exe = Convert.ToString(p["ExecutablePath"]) ?? "";
          bool ours = cmd.IndexOf("ccomics-server.ps1", StringComparison.OrdinalIgnoreCase) >= 0
                   || (string.Equals(exe, rclonePath, StringComparison.OrdinalIgnoreCase) && cmd.Contains("ccomicslib:"));
          if (!ours) continue;
          try { Process.GetProcessById(Convert.ToInt32(p["ProcessId"])).Kill(); } catch { }
        }
      }
      Thread.Sleep(800);
      Run("schtasks.exe", "/Run /TN \"" + TaskName + "\"");
      for (int i = 0; i < 30 && !ServerUp(); i++) Thread.Sleep(500);
    } finally {
      Cursor = Cursors.Default;
    }
    RefreshStatus();
    MessageBox.Show(ServerUp()
        ? (ask ? "서버를 다시 시작했습니다." : "저장했고 서버를 다시 시작했습니다.\n휴대폰 앱에서 서재를 아래로 당겨 새로고침하세요.")
        : "서버가 15초 안에 켜지지 않았습니다. 폴더 경로가 맞는지 확인하세요.\n기록: " + Path.Combine(baseDir, @"pc-server\data\server.log"),
        Text, MessageBoxButtons.OK, ServerUp() ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
  }

  static void Run(string file, string args) {
    var p = Process.Start(new ProcessStartInfo(file, args) { CreateNoWindow = true, UseShellExecute = false });
    p.WaitForExit(10000);
  }

  void CopyCode() {
    object host;
    if (!config.TryGetValue("host", out host) || string.IsNullOrEmpty(Convert.ToString(host))) {
      MessageBox.Show("설정 파일에 PC 주소(host)가 없습니다.", Text);
      return;
    }
    string code = "https://" + Uri.EscapeDataString(Convert.ToString(config["user"])) + ":" + Uri.EscapeDataString(Convert.ToString(config["pass"]))
                + "@" + host + "/ccomics/";
    Clipboard.SetText(code);
    MessageBox.Show("연결 코드를 복사했습니다. 휴대폰 Ccomics → 책 추가 → PC 원격 폴더 연결의 주소 칸에 붙여 넣으세요.\n(비밀번호가 들어 있으니 다른 사람에게 보내지 마세요.)", Text);
  }
}
