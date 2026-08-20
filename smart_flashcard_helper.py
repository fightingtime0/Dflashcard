import customtkinter as ctk
import tkinter.messagebox as messagebox
from tkinter import filedialog
import json
import os
import random
import uuid

# Konfigurasi UI
ctk.set_appearance_mode("System")
ctk.set_default_color_theme("blue")

DATA_FILE = "flashcards_data.json"

class FlashcardData:
    """Kelas untuk menangani penyimpanan dan pemuatan data JSON secara aman."""
    def __init__(self):
        self.data = {"decks": {}}
        self.load_data()

    def load_data(self):
        if os.path.exists(DATA_FILE):
            try:
                with open(DATA_FILE, "r", encoding="utf-8") as f:
                    self.data = json.load(f)
            except json.JSONDecodeError:
                messagebox.showerror("Error", "File data korup. Membuat data baru.")
                self.save_data()
        else:
            self.save_data()

    def save_data(self):
        with open(DATA_FILE, "w", encoding="utf-8") as f:
            json.dump(self.data, f, indent=4, ensure_ascii=False)

    def get_decks(self):
        return list(self.data["decks"].keys())

    def add_deck(self, deck_name):
        if deck_name and deck_name not in self.data["decks"]:
            self.data["decks"][deck_name] = []
            self.save_data()
            return True
        return False

    def delete_deck(self, deck_name):
        if deck_name in self.data["decks"]:
            del self.data["decks"][deck_name]
            self.save_data()

    def get_cards(self, deck_name):
        return self.data["decks"].get(deck_name, [])

    def add_card(self, deck_name, front, back, extra=""):
        card = {
            "id": str(uuid.uuid4()),
            "front": front,
            "back": back,
            "extra": extra, # Kolom penjelasan tambahan
            "level": 0 # 0: Baru/Gagal, 1: Bisa, 2: Mudah
        }
        self.data["decks"][deck_name].append(card)
        self.save_data()

    def update_card_level(self, deck_name, card_id, level):
        for card in self.data["decks"][deck_name]:
            if card["id"] == card_id:
                card["level"] = level
                self.save_data()
                break

    def delete_card(self, deck_name, card_id):
        self.data["decks"][deck_name] = [c for c in self.data["decks"][deck_name] if c["id"] != card_id]
        self.save_data()
        
    def edit_card(self, deck_name, card_id, new_front, new_back, new_extra):
        for card in self.data["decks"][deck_name]:
            if card["id"] == card_id:
                card["front"] = new_front
                card["back"] = new_back
                card["extra"] = new_extra
                self.save_data()
                break

class FlashcardApp(ctk.CTk):
    def __init__(self):
        super().__init__()

        self.title("Smart Flashcard Helper")
        self.geometry("950x650")
        self.minsize(850, 550)

        self.db = FlashcardData()
        self.current_deck = None
        self.study_queue = []
        self.current_card = None
        self.is_showing_back = False
        self.total_session_cards = 0

        self.build_ui()
        self.refresh_deck_list()

    def build_ui(self):
        # Konfigurasi Grid Utama: Sidebar (0) dan Main View (1)
        self.grid_columnconfigure(1, weight=1)
        self.grid_rowconfigure(0, weight=1)

        # ================= SIDEBAR (Manajemen Deck) =================
        self.sidebar_frame = ctk.CTkFrame(self, width=200, corner_radius=0)
        self.sidebar_frame.grid(row=0, column=0, sticky="nsew")
        self.sidebar_frame.grid_rowconfigure(2, weight=1)

        ctk.CTkLabel(self.sidebar_frame, text="Daftar Deck", font=ctk.CTkFont(size=20, weight="bold")).grid(row=0, column=0, padx=20, pady=(20, 10))

        self.deck_listbox = ctk.CTkScrollableFrame(self.sidebar_frame, fg_color="transparent")
        self.deck_listbox.grid(row=1, column=0, sticky="nsew", padx=10, pady=10)

        self.btn_add_deck = ctk.CTkButton(self.sidebar_frame, text="+ Buat Deck Baru", command=self.add_deck_dialog)
        self.btn_add_deck.grid(row=3, column=0, padx=20, pady=10)

        self.btn_delete_deck = ctk.CTkButton(self.sidebar_frame, text="- Hapus Deck", fg_color="#dc3545", hover_color="#c82333", command=self.delete_current_deck)
        self.btn_delete_deck.grid(row=4, column=0, padx=20, pady=(0, 20))

        # ================= MAIN VIEW (Area Belajar) =================
        self.main_frame = ctk.CTkFrame(self, fg_color="transparent")
        self.main_frame.grid(row=0, column=1, sticky="nsew", padx=20, pady=20)
        self.main_frame.grid_columnconfigure(0, weight=1)
        self.main_frame.grid_rowconfigure(1, weight=1)

        # Top Bar Main View
        self.top_bar = ctk.CTkFrame(self.main_frame, fg_color="transparent")
        self.top_bar.grid(row=0, column=0, sticky="ew")
        self.top_bar.grid_columnconfigure(0, weight=1)

        self.lbl_current_deck = ctk.CTkLabel(self.top_bar, text="Pilih Deck di Sidebar", font=ctk.CTkFont(size=24, weight="bold"))
        self.lbl_current_deck.grid(row=0, column=0, sticky="w")

        # Tombol aksi deck
        self.btn_add_card = ctk.CTkButton(self.top_bar, text="+ Tambah Kartu", width=110, command=self.add_card_dialog, state="disabled")
        self.btn_add_card.grid(row=0, column=1, padx=3)
        
        self.btn_import_excel = ctk.CTkButton(self.top_bar, text="Import Excel", width=110, fg_color="#17a2b8", hover_color="#138496", command=self.import_excel_dialog, state="disabled")
        self.btn_import_excel.grid(row=0, column=2, padx=3)
        
        self.btn_edit_card = ctk.CTkButton(self.top_bar, text="Edit Kartu Ini", width=110, fg_color="gray", command=self.edit_card_dialog)
        self.btn_edit_card.grid(row=0, column=3, padx=3)
        
        self.btn_delete_card = ctk.CTkButton(self.top_bar, text="Hapus Kartu", width=110, fg_color="#dc3545", hover_color="#c82333", command=self.delete_current_card)
        self.btn_delete_card.grid(row=0, column=4, padx=3)
        
        self.btn_edit_card.grid_remove() # Sembunyikan awal
        self.btn_delete_card.grid_remove() # Sembunyikan awal

        # ================= KARTU UTAMA (CTkFrame Interaktif) =================
        # Frame pembungkus kartu agar teks ukuran berbeda bisa ditumpuk
        self.card_container = ctk.CTkFrame(
            self.main_frame, 
            fg_color="#2b2b2b",
            corner_radius=20,
            cursor="hand2"
        )
        self.card_container.grid(row=1, column=0, sticky="nsew", pady=30)
        
        # Konfigurasi baris di dalam kartu agar teks selalu berada tepat di tengah secara vertikal
        self.card_container.grid_rowconfigure(0, weight=2) # Spacer Atas
        self.card_container.grid_rowconfigure(1, weight=1) # Konten Utama
        self.card_container.grid_rowconfigure(2, weight=1) # Penjelasan Tambahan
        self.card_container.grid_rowconfigure(3, weight=2) # Spacer Bawah
        self.card_container.grid_columnconfigure(0, weight=1)

        # Label Teks Utama (Depan / Jawaban Belakang)
        self.lbl_card_main = ctk.CTkLabel(
            self.card_container, 
            text="Silakan Pilih Deck\nuntuk Mulai Belajar", 
            font=ctk.CTkFont(size=32, weight="bold"),
            text_color="white",
            wraplength=600,
            justify="center"
        )
        self.lbl_card_main.grid(row=1, column=0, sticky="nsew", padx=30, pady=(10, 5))

        # Label Teks Tambahan (Hanya muncul di sisi belakang)
        self.lbl_card_extra = ctk.CTkLabel(
            self.card_container, 
            text="", 
            font=ctk.CTkFont(size=16, slant="italic"),
            text_color="gray",
            wraplength=600,
            justify="center"
        )
        self.lbl_card_extra.grid(row=2, column=0, sticky="nsew", padx=30, pady=(5, 10))

        # Bind event klik pada Frame & semua Label di dalamnya ke fungsi flip_card
        self.card_container.bind("<Button-1>", lambda e: self.flip_card())
        self.lbl_card_main.bind("<Button-1>", lambda e: self.flip_card())
        self.lbl_card_extra.bind("<Button-1>", lambda e: self.flip_card())

        # Progress Area
        self.lbl_progress = ctk.CTkLabel(self.main_frame, text="", font=ctk.CTkFont(size=14), text_color="gray")
        self.lbl_progress.grid(row=2, column=0, pady=(0, 10))

        # Evaluation Buttons (Bawah) - Disembunyikan saat tampil depan
        self.eval_frame = ctk.CTkFrame(self.main_frame, fg_color="transparent")
        self.eval_frame.grid(row=3, column=0, pady=(0, 20))
        self.eval_frame.grid_remove() # Sembunyikan di awal

        btn_gagal = ctk.CTkButton(self.eval_frame, text="Gagal (Ulangi)", fg_color="#dc3545", hover_color="#c82333", font=ctk.CTkFont(weight="bold"), command=lambda: self.evaluate_card(0))
        btn_gagal.grid(row=0, column=0, padx=10)

        btn_bisa = ctk.CTkButton(self.eval_frame, text="Bisa (Nanti)", fg_color="#007bff", hover_color="#0056b3", font=ctk.CTkFont(weight="bold"), command=lambda: self.evaluate_card(1))
        btn_bisa.grid(row=0, column=1, padx=10)

        btn_mudah = ctk.CTkButton(self.eval_frame, text="Mudah (Jarang)", fg_color="#28a745", hover_color="#218838", font=ctk.CTkFont(weight="bold"), command=lambda: self.evaluate_card(2))
        btn_mudah.grid(row=0, column=2, padx=10)

    # ================= LOGIKA DECK =================
    def refresh_deck_list(self):
        # Bersihkan listbox
        for widget in self.deck_listbox.winfo_children():
            widget.destroy()

        decks = self.db.get_decks()
        for deck in decks:
            btn = ctk.CTkButton(self.deck_listbox, text=deck, fg_color="transparent", text_color=("black", "white"), anchor="w", command=lambda d=deck: self.select_deck(d))
            btn.pack(fill="x", pady=2)

    def add_deck_dialog(self):
        dialog = ctk.CTkInputDialog(text="Masukkan Nama Deck Baru:", title="Buat Deck")
        new_deck = dialog.get_input()
        if new_deck:
            if self.db.add_deck(new_deck):
                self.refresh_deck_list()
                self.select_deck(new_deck)
            else:
                messagebox.showwarning("Peringatan", "Deck dengan nama tersebut sudah ada!")

    def delete_current_deck(self):
        if not self.current_deck:
            return
        confirm = messagebox.askyesno("Hapus Deck", f"Yakin ingin menghapus deck '{self.current_deck}' beserta semua kartunya?")
        if confirm:
            self.db.delete_deck(self.current_deck)
            self.current_deck = None
            self.lbl_current_deck.configure(text="Pilih Deck di Sidebar")
            self.btn_add_card.configure(state="disabled")
            self.btn_import_excel.configure(state="disabled")
            self.lbl_card_main.configure(text="Silakan Pilih Deck\nuntuk Mulai Belajar")
            self.lbl_card_extra.configure(text="")
            self.lbl_progress.configure(text="")
            self.eval_frame.grid_remove()
            self.btn_edit_card.grid_remove()
            self.btn_delete_card.grid_remove()
            self.refresh_deck_list()

    def select_deck(self, deck_name):
        self.current_deck = deck_name
        self.lbl_current_deck.configure(text=deck_name)
        self.btn_add_card.configure(state="normal")
        self.btn_import_excel.configure(state="normal")
        self.start_session()

    # ================= LOGIKA SESI BELAJAR & SPACED REPETITION =================
    def start_session(self):
        cards = self.db.get_cards(self.current_deck)
        if not cards:
            self.card_container.configure(fg_color="#2b2b2b")
            self.lbl_card_main.configure(text="Deck kosong.\nKlik '+ Tambah Kartu' atau 'Import Excel'.", text_color="white")
            self.lbl_card_extra.configure(text="")
            self.lbl_progress.configure(text="")
            self.eval_frame.grid_remove()
            self.btn_edit_card.grid_remove()
            self.btn_delete_card.grid_remove()
            return

        self.study_queue = sorted(cards, key=lambda x: (x.get("level", 0), random.random()))
        self.total_session_cards = len(self.study_queue)
        self.show_next_card()

    def show_next_card(self):
        self.eval_frame.grid_remove()
        self.is_showing_back = False

        if not self.study_queue:
            self.card_container.configure(fg_color="#1f538d")
            self.lbl_card_main.configure(text="🎉 Sesi Selesai! 🎉\nSemua kartu telah direview.", text_color="white")
            self.lbl_card_extra.configure(text="Anda luar biasa!", text_color="#d0d0d0")
            self.btn_edit_card.grid_remove()
            self.btn_delete_card.grid_remove()
            self.current_card = None
            return

        self.current_card = self.study_queue.pop(0)
        
        # Tampilkan Sisi Depan (Pertanyaan)
        self.card_container.configure(fg_color="#2b2b2b")
        self.lbl_card_main.configure(text=self.current_card["front"], text_color="white")
        self.lbl_card_extra.configure(text="") # Sembunyikan penjelasan tambahan di depan
        
        self.btn_edit_card.grid()
        self.btn_delete_card.grid()
        
        sisa = len(self.study_queue) + 1
        self.lbl_progress.configure(text=f"Tersisa: {sisa} kartu dalam antrean")

    def flip_card(self):
        if not self.current_card:
            return
            
        if not self.is_showing_back:
            # Balik ke sisi Belakang (Jawaban)
            self.card_container.configure(fg_color="#e0e0e0")
            self.lbl_card_main.configure(text=self.current_card["back"], text_color="black")
            
            # Tampilkan Penjelasan Tambahan (Kolom 3) dengan warna teks abu-abu agar kontras
            extra_text = self.current_card.get("extra", "")
            if extra_text:
                self.lbl_card_extra.configure(text=extra_text, text_color="#555555")
            else:
                self.lbl_card_extra.configure(text="")
                
            self.is_showing_back = True
            self.eval_frame.grid() # Munculkan tombol evaluasi

    def evaluate_card(self, level):
        self.db.update_card_level(self.current_deck, self.current_card["id"], level)
        
        if level == 0:
            insert_index = min(len(self.study_queue), random.randint(3, 5))
            self.current_card["level"] = 0
            self.study_queue.insert(insert_index, self.current_card)
            
        self.show_next_card()

    # ================= CRUD KARTU (Dialog Kustom) =================
    def add_card_dialog(self):
        self.open_card_dialog("Tambah Kartu", "", "", "", self.save_new_card)

    def edit_card_dialog(self):
        if self.current_card:
            self.open_card_dialog(
                "Edit Kartu", 
                self.current_card["front"], 
                self.current_card["back"], 
                self.current_card.get("extra", ""), 
                self.save_edited_card
            )

    def delete_current_card(self):
        if self.current_card:
            confirm = messagebox.askyesno("Hapus Kartu", "Yakin ingin menghapus kartu ini?")
            if confirm:
                self.db.delete_card(self.current_deck, self.current_card["id"])
                self.show_next_card()

    def open_card_dialog(self, title, default_front, default_back, default_extra, callback):
        """Membuat jendela popup kustom untuk input Depan, Belakang, & Extra"""
        dialog = ctk.CTkToplevel(self)
        dialog.title(title)
        dialog.geometry("450x380")
        dialog.grab_set()
        
        # Posisikan di tengah
        dialog.update_idletasks()
        x = self.winfo_x() + (self.winfo_width() // 2) - 225
        y = self.winfo_y() + (self.winfo_height() // 2) - 190
        dialog.geometry(f"+{x}+{y}")

        ctk.CTkLabel(dialog, text="Sisi Depan (Pertanyaan):").pack(pady=(15, 2))
        txt_front = ctk.CTkTextbox(dialog, height=50)
        txt_front.pack(padx=20, fill="x")
        txt_front.insert("1.0", default_front)

        ctk.CTkLabel(dialog, text="Sisi Belakang (Jawaban Utama):").pack(pady=(10, 2))
        txt_back = ctk.CTkTextbox(dialog, height=50)
        txt_back.pack(padx=20, fill="x")
        txt_back.insert("1.0", default_back)

        # Field baru untuk Penjelasan Tambahan (Kolom 3)
        ctk.CTkLabel(dialog, text="Penjelasan Tambahan / Contoh (Opsional - Font Kecil):").pack(pady=(10, 2))
        txt_extra = ctk.CTkTextbox(dialog, height=50)
        txt_extra.pack(padx=20, fill="x")
        txt_extra.insert("1.0", default_extra)

        def on_save():
            front_text = txt_front.get("1.0", "end-1c").strip()
            back_text = txt_back.get("1.0", "end-1c").strip()
            extra_text = txt_extra.get("1.0", "end-1c").strip()
            if front_text and back_text:
                callback(front_text, back_text, extra_text)
                dialog.destroy()
            else:
                messagebox.showwarning("Peringatan", "Depan dan Belakang tidak boleh kosong!", parent=dialog)

        ctk.CTkButton(dialog, text="Simpan", command=on_save).pack(pady=15)

    def save_new_card(self, front, back, extra):
        self.db.add_card(self.current_deck, front, back, extra)
        if not self.study_queue and not self.current_card:
            self.start_session()
        else:
            messagebox.showinfo("Sukses", "Kartu berhasil ditambahkan!")

    def save_edited_card(self, front, back, extra):
        self.db.edit_card(self.current_deck, self.current_card["id"], front, back, extra)
        self.current_card["front"] = front
        self.current_card["back"] = back
        self.current_card["extra"] = extra
        
        if self.is_showing_back:
            self.lbl_card_main.configure(text=back)
            self.lbl_card_extra.configure(text=extra)
        else:
            self.lbl_card_main.configure(text=front)
            self.lbl_card_extra.configure(text="")

    # ================= LOGIKA IMPORT FILE EXCEL (BARU) =================
    def import_excel_dialog(self):
        if not self.current_deck:
            messagebox.showwarning("Peringatan", "Pilih atau buat deck terlebih dahulu di sidebar!")
            return
            
        file_path = filedialog.askopenfilename(
            title="Pilih File Excel",
            filetypes=(("Excel Files", "*.xlsx;*.xls"), ("Semua File", "*.*"))
        )
        if not file_path:
            return

        try:
            import openpyxl
        except ImportError:
            messagebox.showerror(
                "Error", 
                "Library 'openpyxl' belum terinstal.\n\nInstal terlebih dahulu dengan menjalankan perintah ini di CMD:\npip install openpyxl"
            )
            return

        try:
            wb = openpyxl.load_workbook(file_path, data_only=True)
            sheet = wb.active
            
            imported_count = 0
            for idx, row in enumerate(sheet.iter_rows(values_only=True)):
                # Deteksi baris header (misal baris 1 berisi tulisan 'depan/front') lalu lewati
                if idx == 0 and row[0]:
                    cell_val = str(row[0]).strip().lower()
                    if "front" in cell_val or "depan" in cell_val or "pertanyaan" in cell_val:
                        continue
                
                # Pastikan minimal Kolom 1 (Depan) dan Kolom 2 (Belakang) terisi
                if not row or row[0] is None or row[1] is None:
                    continue
                
                front = str(row[0]).strip()
                back = str(row[1]).strip()
                extra = str(row[2]).strip() if len(row) > 2 and row[2] is not None else ""
                
                if front and back:
                    self.db.add_card(self.current_deck, front, back, extra)
                    imported_count += 1
            
            if imported_count > 0:
                messagebox.showinfo("Sukses", f"Berhasil mengimpor {imported_count} kartu ke dalam deck '{self.current_deck}'!")
                self.start_session() # Muat ulang sesi belajar dengan kartu baru
            else:
                messagebox.showwarning("Peringatan", "Tidak ditemukan baris data yang valid di file Excel tersebut.")
                
        except Exception as e:
            messagebox.showerror("Error", f"Gagal membaca file Excel:\n{str(e)}")

if __name__ == "__main__":
    ctk.deactivate_automatic_dpi_awareness()
    app = FlashcardApp()
    app.mainloop()