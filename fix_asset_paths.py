import zipfile, shutil, copy, sys

src = sys.argv[1]
tmp = src + ".fix"
zin = zipfile.ZipFile(src)
zout = zipfile.ZipFile(tmp, "w")
for i in zin.infolist():
    z = copy.copy(i)
    z.filename = i.filename.replace("\\", "/")
    zout.writestr(z, zin.read(i.filename))
zout.close()
zin.close()
shutil.move(tmp, src)
print("asset paths fixed")
