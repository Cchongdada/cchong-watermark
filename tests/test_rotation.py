import io
import json
import unittest
from PIL import Image
from fastapi import HTTPException
from fastapi.testclient import TestClient
from app.main import app, parse_settings, rotate_photo, still_image


class RotationTest(unittest.TestCase):
    def setUp(self):
        self.image = Image.new("RGB", (160, 100), "white")
        self.image.paste((255,0,0),(0,0,80,50))
        self.image.paste((0,255,0),(80,0,160,50))
        self.image.paste((0,0,255),(0,50,80,100))
        self.image.paste((255,255,0),(80,50,160,100))

    def encoded(self, orientation=1):
        output=io.BytesIO(); exif=Image.Exif();exif[274]=orientation
        self.image.save(output,format="JPEG",quality=100,subsampling=0,exif=exif)
        return output.getvalue()

    def test_validation(self):
        for value in (-90,45,360,True,"90",None):
            with self.assertRaises(HTTPException): parse_settings(json.dumps({"rotation":value}))
        self.assertEqual(parse_settings("{}")['rotation'],0)

    def test_rotation_before_watermark(self):
        for angle in (0,90,180,270):
            options=parse_settings(json.dumps({"rotation":angle}))
            result,overlay=still_image(self.encoded(),"black",settings=options)
            actual=Image.open(io.BytesIO(result));expected=rotate_photo(self.image,options)
            self.assertEqual(actual.size,expected.size)
            self.assertEqual(overlay.size,actual.size)
            self.assertEqual(actual.getexif()[274],1)
            a,b=actual.getpixel((10,10)),expected.getpixel((10,10))
            self.assertLess(max(abs(x-y) for x,y in zip(a,b)),12)

    def test_exif_then_manual_and_compare(self):
        client=TestClient(app)
        # EXIF clockwise90 plus manual clockwise90 = 180.
        options={"rotation":90}
        response=client.post("/api/preview",files={"file":("scan.jpg",self.encoded(6),"image/jpeg")},
                             data={"settings":json.dumps(options),"original":"true"})
        self.assertEqual(response.status_code,200)
        image=Image.open(io.BytesIO(response.content))
        self.assertEqual(image.size,(160,100))
        self.assertGreater(image.getpixel((10,10))[0],240)
        self.assertGreater(image.getpixel((10,10))[1],240)
        self.assertLess(image.getpixel((10,10))[2],15)


if __name__=="__main__": unittest.main()
