# 胶卷库维护说明

共 16 品牌、123 型号。中文辅助名称包含常见译名、昵称和类型说明，并非全部为厂商正式中文名称。水印只使用英文 name。

品牌来源与型号数量：

- Kodak：25 款；[资料 1](https://kodakprofessional.com/photographers/resources)、[资料 2](https://www.kodak.com/en/motion/products/camera-films/)、[资料 3](https://business.kodakmoments.com/resource/film-technical-publications)
- Fujifilm：23 款；[资料 1](https://asset.fujifilm.com/www/us/files/2020-03/d52487c5c6f84e7f935c299491c5c1ff/ProfessionalFilmDataGuide.pdf)
- Ilford：9 款；[资料 1](https://www.ilfordphoto.com/technical-data/)
- Kentmere：3 款；[资料 1](https://www.ilfordphoto.com/technical-data/)
- CineStill：4 款；[资料 1](https://cinestillfilm.com/collections/film)
- Lomography：17 款；[资料 1](https://www.lomography.com/magazine/327296-a-guide-to-lomography-films-and-formats)
- Lucky：3 款；[资料 1](https://www.lucky.cn/)
- Yes!Star：2 款；[资料 1](https://afilm.me/shop)
- Foma：5 款；[资料 1](https://www.foma.cz/en/catalogue_bw_photo_materials_and_developing_information)
- Rollei：8 款；[资料 1](https://www.rolleianalog.com/products/?lang=en)
- ADOX：4 款；[资料 1](https://www.adox.de/en/)
- Harman：4 款；[资料 1](https://www.harmanphoto.co.uk/harman-colour-film)
- ORWO：4 款；[资料 1](https://orwo.shop/collections/all)、[资料 2](https://filmotec.de/wp-content/uploads/2023/04/Datenblatt-Wolfen-NC500.pdf)
- ReflxLab：7 款；[资料 1](https://reflxlab.com/collections/all-film)
- AgfaPhoto：2 款；[资料 1](https://agfaphoto.com/en/analog-photography-single-use-cameras/)
- Ferrania：3 款；[资料 1](https://www.filmferrania.com/)

历史/特殊说明：

- 历史型号用于旧扫描件，目录不承诺在售状态。
- 中文类型说明与来源备注仅用于检索，不拼进水印。
- D/T 表示平衡光源；电影分装卷的实际标法、冲洗工艺和是否去碳以手中包装为准。
- Delta 3200、T-Max P3200 使用 EI 标法；LomoChrome 100–400 是厂商给出的曝光范围，未强制设为单一 ISO。
- Yes!Star 200/400 的公开资料以产品销售页面为依据，不推断乳剂来源或代工关系。
- 型号库未收录确认不足的分装别名，仍可在品牌和型号输入框手工填写英文。

更新时修改 app/static/films.json，并将完全相同的数据同步至 android/app/src/main/assets/films.json。检查 name 唯一、英文水印名称、brand/model 拼接一致，保留来源 URL。
