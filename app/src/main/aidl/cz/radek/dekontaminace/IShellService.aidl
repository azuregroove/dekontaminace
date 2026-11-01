package cz.radek.dekontaminace;

interface IShellService {

    // Kód transakce je pevně daný serverem Shizuku, volá ho při odpojení služby.
    void destroy() = 16777114;

    // Vrací "<návratový kód>\n<stdout+stderr>".
    String exec(String command) = 1;
}
