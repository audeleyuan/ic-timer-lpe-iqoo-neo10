#include <stdio.h>
#include "ic_syms.h"

int main(int argc, char **argv)
{
	if (argc < 3) {
		fprintf(stderr, "usage: %s <image|boot.img|vmlinux|System.map> <out>\n",
			argv[0]);
		return 2;
	}
	return ic_syms_parse_file(argv[1], argv[2], 1) ? 1 : 0;
}
