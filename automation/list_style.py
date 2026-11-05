"""Shared native list styling and a spacious document outline."""
from tkinter import ttk


def apply_list_style(root, style):
    # The combobox popdown is a separate Tk Listbox, not a themed ttk widget.
    for option, value in {'background':'#19211e', 'foreground':'#c6d8cf',
                          'selectBackground':'#294a3c', 'selectForeground':'#a3f2d2',
                          'selectBorderWidth':0, 'borderWidth':0,
                          'highlightThickness':0, 'relief':'flat',
                          'font':('맑은 고딕',11)}.items():
        root.option_add('*TCombobox*Listbox.'+option, value)
    style.configure('TCombobox', padding=(12,9), arrowsize=12,
                    fieldbackground='#19211e', background='#19211e',
                    foreground='#c6d8cf', arrowcolor='#91b3a2',
                    bordercolor='#304238', lightcolor='#19211e', darkcolor='#19211e')
    style.map('TCombobox', fieldbackground=[('readonly','#19211e')],
              foreground=[('readonly','#c6d8cf')],
              background=[('active','#22382d')],
              bordercolor=[('focus','#73e2bb'),('active','#4c7561')],
              selectbackground=[('readonly','#19211e')],
              selectforeground=[('readonly','#c6d8cf')])
    style.configure('Treeview', rowheight=44, padding=6, relief='flat',
                    font=('맑은 고딕',10), background='#141b18',
                    fieldbackground='#141b18', foreground='#c6d8cf', borderwidth=0)
    style.layout('Treeview', [('Treeview.treearea', {'sticky':'nswe'})])
    style.configure('Treeview.Heading', font=('맑은 고딕',9),
                    foreground='#91a99d', background='#19211e',
                    relief='flat', borderwidth=0, padding=(12,12))
    style.map('Treeview.Heading', background=[('active','#22382d')])
    style.map('Treeview', background=[('selected','#294a3c')],
              foreground=[('selected','#a3f2d2')])
    style.configure('Outline.Treeview', rowheight=40, padding=4)


class OutlineList(ttk.Treeview):
    """Listbox-compatible outline with comfortable row spacing and keyboard navigation."""
    def __init__(self, parent):
        super().__init__(parent, show='tree', selectmode='browse', style='Outline.Treeview')
        self.column('#0', width=190, minwidth=100)
        self.bind('<<TreeviewSelect>>', lambda e:self.event_generate('<<ListboxSelect>>'))

    def delete(self, first, last=None):
        children=self.get_children()
        if children:
            super().delete(*children)

    def insert(self, index, text):
        return super().insert('', 'end', text='  '+text)

    def curselection(self):
        selected=self.selection()
        return (self.index(selected[0]),) if selected else ()
