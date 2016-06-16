Summary:        PL/R module for HAWQ
Name:           plr-hawq
Version:        08.03.00.14
Release:		0
Prefix:         /usr/local
License:		GPL

%description
The PL/R modules provides Procedural language implementation of R for HAWQ.

%install
# Note:
# 1. We do not use DESTDIR to specify the installation path since the binaries will be
#    installed in $DESTDIR/$prefix instead of $DESTDIR.
# 2. We build using the "configured" prefix at first and then install it using
#    the new prefix.
make -C %{plr_srcdir}
make -C %{plr_srcdir} install prefix=%{buildroot}/usr/local

%files
/usr/local
